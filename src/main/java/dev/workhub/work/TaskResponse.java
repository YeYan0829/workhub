package dev.workhub.work;

import java.time.*;
import java.util.UUID;

public record TaskResponse(
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
        Instant createdAt,
        Instant updatedAt,
        boolean isBlocked,
        boolean dependencyRisk,
        String pending,
        boolean triageRequired) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.id(),
                task.projectId(),
                task.title(),
                task.description(),
                task.status(),
                task.type(),
                task.priority(),
                task.assigneeId(),
                task.dueDate(),
                task.origin(),
                task.revision(),
                task.discussionRevision(),
                task.createdAt(),
                task.updatedAt(),
                task.isBlocked(),
                TaskSemantics.dependencyRisk(task.status(), task.isBlocked()),
                null,
                task.triageRequired());
    }
}
