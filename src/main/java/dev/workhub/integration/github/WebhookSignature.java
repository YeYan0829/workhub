package dev.workhub.integration.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    private WebhookSignature() {}

    public static boolean valid(byte[] body, String header, String secret) {
        if (secret == null
                || secret.isBlank()
                || header == null
                || !header.matches("sha256=[0-9a-f]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(
                    mac.doFinal(body), HexFormat.of().parseHex(header.substring(7)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
