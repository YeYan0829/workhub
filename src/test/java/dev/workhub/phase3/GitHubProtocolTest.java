package dev.workhub.phase3;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.integration.github.*;
import dev.workhub.work.TaskStatus;

import okhttp3.mockwebserver.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class GitHubProtocolTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void signatureUsesExactBytes() {
        String signature =
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        assertThat(
                        WebhookSignature.valid(
                                "Hello, World!".getBytes(),
                                signature,
                                "It's a Secret to Everybody"))
                .isTrue();
        assertThat(
                        WebhookSignature.valid(
                                "Hello, World! ".getBytes(),
                                signature,
                                "It's a Secret to Everybody"))
                .isFalse();
        assertThat(WebhookSignature.valid("Hello, World!".getBytes(), signature, "")).isFalse();
        assertThat(WebhookSignature.valid(new byte[0], null, "secret")).isFalse();
    }

    IssueSnapshot snapshot(String state, String reason) {
        return new IssueSnapshot(1, 1, "a", "", state, reason, Instant.EPOCH, "");
    }

    @Test
    void mappingKeepsOpenWorkflowAndRejectsUnknownReason() {
        assertThat(snapshot("open", null).status(null, null)).isEqualTo(TaskStatus.TRIAGE);
        assertThat(snapshot("open", null).status(TaskStatus.IN_PROGRESS, "open"))
                .isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(snapshot("open", null).status(TaskStatus.CANCELED, "closed"))
                .isEqualTo(TaskStatus.TODO);
        assertThat(snapshot("closed", null).status(null, null)).isEqualTo(TaskStatus.DONE);
        assertThat(snapshot("closed", "duplicate").status(null, null))
                .isEqualTo(TaskStatus.CANCELED);
        assertThatThrownBy(() -> snapshot("open", "future").status(TaskStatus.DONE, "closed"))
                .hasMessage("ERROR_UNSUPPORTED_REMOTE_STATE");
        assertThatThrownBy(() -> snapshot("closed", "future").status(null, null))
                .hasMessage("ERROR_UNSUPPORTED_REMOTE_STATE");
    }

    @Test
    void patchWhitelistAndPrExclusion() throws Exception {
        assertThatThrownBy(() -> IssueSnapshot.validatePatch(json.readTree("{\"assignees\":[1]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> IssueSnapshot.validatePatch(json.readTree("{\"state\":\"closed\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IssueSnapshot.parse(json.readTree("{\"pull_request\":{}}")))
                .hasMessage("PULL_REQUEST");
        IssueSnapshot.validatePatch(
                json.readTree("{\"state\":\"closed\",\"state_reason\":\"not_planned\"}"));
    }

    @Test
    void httpContractMinimalPatchRateLimitAndNoRedirect() throws Exception {
        try (var fake = new MockWebServer()) {
            fake.start();
            var client = new GitHubClient(json, fake.url("/").toString(), "", "", "fake", true);
            fake.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            client.request(
                    1,
                    "PATCH",
                    "/repos/o/r/issues/1",
                    json.readTree("{\"state\":\"closed\",\"state_reason\":\"completed\"}"));
            var sent = fake.takeRequest();
            assertThat(sent.getMethod()).isEqualTo("PATCH");
            assertThat(json.readTree(sent.getBody().readUtf8()).size()).isEqualTo(2);
            assertThat(sent.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
            fake.enqueue(
                    new MockResponse()
                            .setResponseCode(403)
                            .setHeader("Retry-After", "60")
                            .setBody("{}"));
            var rate = client.request(1, "GET", "/repos/o/r/issues/1", null);
            assertThat(rate.retryableRead()).isTrue();
            assertThat(rate.retryAt()).isAfter(Instant.now().plusSeconds(50));
            fake.enqueue(
                    new MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", "https://example.com")
                            .setBody("{}"));
            assertThat(client.request(1, "GET", "/repos/o/r/issues/1", null).status())
                    .isEqualTo(302);
            assertThat(fake.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void disabledWritesDoNotReachHttp() throws Exception {
        try (var fake = new MockWebServer()) {
            fake.start();
            var client = new GitHubClient(json, fake.url("/").toString(), "", "", "fake", false);
            assertThat(
                            client.request(
                                            1,
                                            "PATCH",
                                            "/repos/o/r/issues/1",
                                            json.createObjectNode())
                                    .error())
                    .isEqualTo("DISPATCH_DISABLED");
            assertThat(fake.getRequestCount()).isZero();
        }
    }

    @Test
    void installationJwtAndTokenCacheUseProviderExpiry() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var file = java.nio.file.Files.createTempFile("workhub-fake-app-", ".pem");
        java.nio.file.Files.writeString(
                file,
                "-----BEGIN PRIVATE KEY-----\n"
                        + java.util.Base64.getEncoder()
                                .encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----");
        try (var fake = new MockWebServer()) {
            fake.start();
            var client =
                    new GitHubClient(
                            json, fake.url("/").toString(), "123", file.toString(), "", false);
            fake.enqueue(
                    new MockResponse()
                            .setBody(
                                    "{\"token\":\"test-installation-token\",\"expires_at\":\""
                                            + Instant.now().plusSeconds(3600)
                                            + "\"}"));
            fake.enqueue(new MockResponse().setBody("{}"));
            fake.enqueue(new MockResponse().setBody("{}"));
            assertThat(client.request(10, "GET", "/repos/o/r", null).ok()).isTrue();
            assertThat(client.request(10, "GET", "/repos/o/r", null).ok()).isTrue();
            var token = fake.takeRequest();
            assertThat(token.getMethod()).isEqualTo("POST");
            assertThat(token.getPath()).isEqualTo("/app/installations/10/access_tokens");
            String jwt = token.getHeader("Authorization").substring(7);
            String[] parts = jwt.split("\\.");
            var verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pair.getPublic());
            verifier.update(
                    (parts[0] + "." + parts[1])
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThat(verifier.verify(java.util.Base64.getUrlDecoder().decode(parts[2]))).isTrue();
            assertThat(
                            json.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]))
                                    .path("iss")
                                    .asText())
                    .isEqualTo("123");
            assertThat(fake.takeRequest().getHeader("Authorization"))
                    .isEqualTo("Bearer test-installation-token");
            assertThat(fake.getRequestCount()).isEqualTo(3);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
