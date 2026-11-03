package dev.workhub.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Inputs {
    private Inputs() {}

    public static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw ApiException.invalid(
                    field + " is required and must be at most " + max + " characters");
        return value;
    }

    public static long revision(Long value) {
        if (value == null)
            throw new ApiException(428, "PRECONDITION_REQUIRED", "Expected revision is required");
        if (value < 0) throw ApiException.invalid("Revision must be non-negative");
        return value;
    }

    public static long ifMatch(String value) {
        if (value == null) return revision(null);
        if (!value.matches("(?:[0-9]+|\"[0-9]+\")"))
            throw ApiException.invalid("If-Match must contain one revision");
        try {
            return revision(Long.parseLong(value.replace("\"", "")));
        } catch (NumberFormatException e) {
            throw ApiException.invalid("Invalid revision");
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int limit(int value) {
        if (value < 1 || value > 100) throw ApiException.invalid("limit must be between 1 and 100");
        return value;
    }
}
