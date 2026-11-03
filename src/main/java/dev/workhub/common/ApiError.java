package dev.workhub.common;

import java.util.List;
import java.util.UUID;

public record ApiError(
        String code,
        String message,
        String requestId,
        Long currentRevision,
        boolean retryable,
        boolean refreshRequired,
        List<UUID> path) {
    public static ApiError from(ApiException error) {
        return new ApiError(
                error.code,
                error.getMessage(),
                UUID.randomUUID().toString(),
                error.currentRevision,
                error.status == 503,
                error.status == 409,
                error.path);
    }
}
