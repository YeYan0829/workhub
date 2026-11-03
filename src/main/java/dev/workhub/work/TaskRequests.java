package dev.workhub.work;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.time.LocalDate;
import java.util.UUID;

public final class TaskRequests {
    private TaskRequests() {}

    public record Create(
            String title,
            String description,
            TaskStatus status,
            TaskType type,
            Integer priority,
            UUID assigneeId,
            LocalDate dueDate) {}

    // PATCH distinguishes omitted nullable fields from explicit JSON null (clear).
    public static class Patch {
        private String title, description;
        private TaskStatus status;
        private TaskType type;
        private Integer priority;
        private UUID assigneeId;
        private LocalDate dueDate;
        private boolean assigneePresent, dueDatePresent;

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        public TaskStatus status() {
            return status;
        }

        public TaskType type() {
            return type;
        }

        public Integer priority() {
            return priority;
        }

        public UUID assigneeId() {
            return assigneeId;
        }

        public LocalDate dueDate() {
            return dueDate;
        }

        public boolean assigneePresent() {
            return assigneePresent;
        }

        public boolean dueDatePresent() {
            return dueDatePresent;
        }

        @JsonSetter
        public void setTitle(String v) {
            if (v == null) throw new IllegalArgumentException("title cannot be null");
            title = v;
        }

        @JsonSetter
        public void setDescription(String v) {
            if (v == null) throw new IllegalArgumentException("description cannot be null");
            description = v;
        }

        @JsonSetter
        public void setStatus(TaskStatus v) {
            if (v == null) throw new IllegalArgumentException("status cannot be null");
            status = v;
        }

        @JsonSetter
        public void setType(TaskType v) {
            if (v == null) throw new IllegalArgumentException("type cannot be null");
            type = v;
        }

        @JsonSetter
        public void setPriority(Integer v) {
            if (v == null) throw new IllegalArgumentException("priority cannot be null");
            priority = v;
        }

        @JsonSetter
        public void setAssigneeId(UUID v) {
            assigneeId = v;
            assigneePresent = true;
        }

        @JsonSetter
        public void setDueDate(LocalDate v) {
            dueDate = v;
            dueDatePresent = true;
        }
    }
}
