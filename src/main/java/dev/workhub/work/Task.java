package dev.workhub.work;

import java.time.*;
import java.util.UUID;

public record Task(
        UUID id,
        UUID projectId,
        String title,
        String description,
        TaskStatus status,
        TaskType type,
        int priority,
        UUID assigneeId,
        LocalDate dueDate,
        TaskOrigin origin,
        long revision,
        long discussionRevision,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean isBlocked,
        boolean triageRequired) {}
