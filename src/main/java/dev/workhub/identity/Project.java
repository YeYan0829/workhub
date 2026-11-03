package dev.workhub.identity;

import java.time.Instant;
import java.util.UUID;

public record Project(
        UUID id,
        UUID workspaceId,
        String name,
        long businessRevision,
        Instant createdAt,
        String description,
        boolean archived) {}
