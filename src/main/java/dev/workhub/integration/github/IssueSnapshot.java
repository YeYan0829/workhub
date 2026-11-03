package dev.workhub.integration.github;

import com.fasterxml.jackson.databind.JsonNode;

import dev.workhub.work.TaskStatus;

import java.time.Instant;
import java.util.*;

/** Canonical shared fields. A timestamp is an old-observation guard, never a CAS version. */
public record IssueSnapshot(
        long id,
        long number,
        String title,
        String body,
        String state,
        String reason,
        Instant updatedAt,
        String url) {
    public static IssueSnapshot parse(JsonNode n) {
        if (n.has("pull_request")) throw new IllegalArgumentException("PULL_REQUEST");
        if (n.path("id").asLong() <= 0
                || n.path("number").asLong() <= 0
                || !n.path("title").isTextual()
                || n.path("title").asText().length() > 500
                || !(n.path("body").isTextual() || n.path("body").isNull()))
            throw new IllegalArgumentException("INVALID_ISSUE");
        var s =
                new IssueSnapshot(
                        n.path("id").asLong(),
                        n.path("number").asLong(),
                        n.path("title").asText(),
                        n.path("body").asText(""),
                        n.path("state").asText(),
                        n.path("state_reason").isNull() || n.path("state_reason").isMissingNode()
                                ? null
                                : n.path("state_reason").asText(),
                        Instant.parse(n.path("updated_at").asText()),
                        n.path("html_url").asText());
        s.status(null, null); // Reject unexplainable states before publishing facts.
        return s;
    }

    public TaskStatus status(TaskStatus current, String previousRemoteState) {
        if (reason != null
                && !Set.of("completed", "not_planned", "duplicate", "reopened").contains(reason))
            throw new IllegalArgumentException("ERROR_UNSUPPORTED_REMOTE_STATE");
        if (state.equals("open")) {
            if (current == null) return TaskStatus.TRIAGE;
            if ("closed".equals(previousRemoteState)
                    || current == TaskStatus.DONE
                    || current == TaskStatus.CANCELED) return TaskStatus.TODO;
            return current;
        }
        if (!state.equals("closed"))
            throw new IllegalArgumentException("ERROR_UNSUPPORTED_REMOTE_STATE");
        if (reason == null || reason.equals("completed")) return TaskStatus.DONE;
        if (reason.equals("not_planned") || reason.equals("duplicate")) return TaskStatus.CANCELED;
        throw new IllegalArgumentException("ERROR_UNSUPPORTED_REMOTE_STATE");
    }

    public Map<String, Object> shared() {
        var m = new LinkedHashMap<String, Object>();
        m.put("title", title);
        m.put("body", body);
        m.put("state", state);
        m.put("state_reason", reason);
        return m;
    }

    public boolean satisfies(JsonNode patch) {
        var fields = shared();
        var it = patch.fields();
        while (it.hasNext()) {
            var e = it.next();
            Object expected = e.getValue().isNull() ? null : e.getValue().asText();
            if (e.getKey().equals("body") && expected == null) expected = "";
            if (!Objects.equals(fields.get(e.getKey()), expected)) return false;
        }
        return true;
    }

    public static void validatePatch(JsonNode patch) {
        if (!patch.isObject() || patch.isEmpty()) throw new IllegalArgumentException("Empty patch");
        patch.fields()
                .forEachRemaining(
                        e -> {
                            if (!Set.of("title", "body", "state", "state_reason")
                                            .contains(e.getKey())
                                    || !(e.getValue().isTextual()
                                            || e.getKey().equals("body") && e.getValue().isNull()))
                                throw new IllegalArgumentException("Invalid shared field");
                        });
        if (patch.has("title")
                && (patch.get("title").asText().isBlank()
                        || patch.get("title").asText().length() > 500))
            throw new IllegalArgumentException("Invalid title");
        if (patch.has("body") && patch.get("body").asText("").length() > 1_000_000)
            throw new IllegalArgumentException("Body too large");
        if (patch.has("state") && !Set.of("open", "closed").contains(patch.get("state").asText()))
            throw new IllegalArgumentException("Invalid state");
        if (patch.has("state_reason")
                && (!patch.path("state").asText().equals("closed")
                        || !Set.of("completed", "not_planned")
                                .contains(patch.path("state_reason").asText())))
            throw new IllegalArgumentException("Invalid state reason");
        if (patch.path("state").asText().equals("closed") && !patch.has("state_reason"))
            throw new IllegalArgumentException("Closing requires state_reason");
    }
}
