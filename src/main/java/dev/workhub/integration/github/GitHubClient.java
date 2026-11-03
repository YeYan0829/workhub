package dev.workhub.integration.github;

import com.fasterxml.jackson.databind.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** One HTTP attempt, no transparent write retry, redirects or response-body logging. */
@Component
public class GitHubClient {
    public record Reply(
            int status,
            JsonNode body,
            String etag,
            String requestId,
            Instant retryAt,
            String error,
            boolean nextPage) {
        public Reply(
                int status,
                JsonNode body,
                String etag,
                String requestId,
                Instant retryAt,
                String error) {
            this(status, body, etag, requestId, retryAt, error, false);
        }

        public boolean ok() {
            return status >= 200 && status < 300 && error == null;
        }

        public boolean retryableRead() {
            return status == 0
                    || status >= 500
                    || status == 429
                    || status == 403 && retryAt != null;
        }
    }

    private record Token(String value, Instant expires) {}

    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final URI base;
    private final String appId, keyFile, fakeToken;
    private final boolean writes;
    private final Map<Long, Token> tokens = new HashMap<>();

    public GitHubClient(
            ObjectMapper json,
            @Value("${workhub.github.api-base:https://api.github.com}") String base,
            @Value("${workhub.github.app-id:}") String appId,
            @Value("${workhub.github.private-key-file:}") String keyFile,
            @Value("${workhub.github.fake-token:}") String fakeToken,
            @Value("${workhub.github.dispatch-enabled:false}") boolean writes) {
        this.json = json;
        this.base = URI.create(base);
        this.appId = appId;
        this.keyFile = keyFile;
        this.fakeToken = fakeToken;
        this.writes = writes;
        boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]").contains(this.base.getHost());
        if (this.base.getUserInfo() != null
                || this.base.getQuery() != null
                || this.base.getFragment() != null
                || !("https".equals(this.base.getScheme())
                                && "api.github.com".equals(this.base.getHost())
                        || loopback))
            throw new IllegalArgumentException("GitHub API must be api.github.com or a local fake");
        if (!fakeToken.isBlank() && !loopback)
            throw new IllegalArgumentException("Fake credentials require loopback");
    }

    public boolean writesEnabled() {
        return writes;
    }

    public Reply request(long installation, String method, String path, JsonNode body) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("HTTP inside business transaction");
        if (!path.startsWith("/repos/") || path.contains("..") || path.contains("#"))
            throw new IllegalArgumentException("Invalid provider path");
        if (!Set.of("GET", "PATCH").contains(method))
            throw new IllegalArgumentException("Unsupported method");
        if (method.equals("PATCH") && !writes) return failure(0, "DISPATCH_DISABLED");
        try {
            String token = token(installation);
            Reply result = send(method, path, body, token);
            if (result.status() == 401)
                synchronized (this) {
                    tokens.remove(installation);
                }
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return failure(0, "IO_OR_AUTH_CONFIGURATION");
        }
    }

    private Reply failure(int status, String code) {
        return new Reply(status, json.nullNode(), null, null, null, code);
    }

    private synchronized String token(long installation) throws Exception {
        if (!fakeToken.isBlank()) return fakeToken;
        Token cached = tokens.get(installation);
        if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(60)))
            return cached.value();
        if (appId.isBlank() || keyFile.isBlank())
            throw new IllegalStateException("GitHub App credentials missing");
        String pem =
                Files.readString(Path.of(keyFile))
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        PrivateKey key =
                KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        long now = Instant.now().getEpochSecond();
        var enc = Base64.getUrlEncoder().withoutPadding();
        String unsigned =
                enc.encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8))
                        + "."
                        + enc.encodeToString(
                                json.writeValueAsBytes(
                                        Map.of("iat", now - 60, "exp", now + 540, "iss", appId)));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        Reply r =
                send(
                        "POST",
                        "/app/installations/" + installation + "/access_tokens",
                        json.createObjectNode(),
                        unsigned + "." + enc.encodeToString(signature.sign()));
        if (!r.ok()) throw new IllegalStateException("Installation token rejected");
        Token fresh =
                new Token(
                        r.body().path("token").asText(),
                        Instant.parse(r.body().path("expires_at").asText()));
        if (fresh.value().isBlank()) throw new IllegalStateException("Empty installation token");
        tokens.put(installation, fresh);
        return fresh.value();
    }

    private Reply send(String method, String path, JsonNode body, String token) throws Exception {
        var request =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28");
        if (method.equals("GET")) request.GET();
        else
            request.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Instant retry = null;
        String after = response.headers().firstValue("Retry-After").orElse(null);
        try {
            if (after != null) retry = Instant.now().plusSeconds(Long.parseLong(after));
            else if (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0"))
                retry =
                        Instant.ofEpochSecond(
                                Long.parseLong(
                                        response.headers()
                                                .firstValue("X-RateLimit-Reset")
                                                .orElse("0")));
        } catch (RuntimeException ignored) {
            retry = Instant.now().plusSeconds(60);
        }
        if (response.statusCode() == 403
                && retry == null
                && response.body().toLowerCase(java.util.Locale.ROOT).contains("rate limit"))
            retry = Instant.now().plusSeconds(60);
        if (response.statusCode() == 429 && retry == null) retry = Instant.now().plusSeconds(60);
        JsonNode parsed;
        try {
            parsed = json.readTree(response.body());
        } catch (Exception e) {
            return new Reply(
                    response.statusCode(),
                    json.nullNode(),
                    null,
                    response.headers().firstValue("X-GitHub-Request-Id").orElse(null),
                    retry,
                    "INVALID_PROVIDER_JSON");
        }
        return new Reply(
                response.statusCode(),
                parsed == null ? json.nullNode() : parsed,
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("X-GitHub-Request-Id").orElse(null),
                retry,
                null,
                response.headers().firstValue("Link").orElse("").contains("rel=\"next\""));
    }
}
