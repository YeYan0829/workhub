package dev.workhub.common;

import java.util.List;
import java.util.UUID;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Long currentRevision;
    public final List<UUID> path;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null, List.of());
    }

    public ApiException(int status, String code, String message, Long revision, List<UUID> path) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentRevision = revision;
        this.path = List.copyOf(path);
    }

    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "Access denied");
    }

    public static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "Object not found in this project");
    }

    public static ApiException invalid(String message) {
        return new ApiException(422, "VALIDATION_ERROR", message);
    }

    public static ApiException conflict(String code, long revision) {
        return new ApiException(
                409, code, "Refresh the current state before retrying", revision, List.of());
    }

    public static ApiException limit() {
        return new ApiException(
                503,
                "DEPENDENCY_CHECK_LIMIT",
                "Dependency validation did not complete; no changes committed");
    }
}
