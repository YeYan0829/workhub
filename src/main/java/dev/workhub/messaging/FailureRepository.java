package dev.workhub.messaging;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class FailureRepository {
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final WorkJobRepository jobs;
    private final Json json;

    public FailureRepository(
            JdbcTemplate jdbc, OutboxRepository outbox, WorkJobRepository jobs, Json json) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.jobs = jobs;
        this.json = json;
    }

    public void record(
            String key, UUID job, UUID message, String category, String ingress, byte[] body) {
        UUID id = UUID.randomUUID();
        String fingerprint = Inputs.hash(Base64.getEncoder().encodeToString(body));
        String sample =
                Base64.getEncoder()
                        .encodeToString(Arrays.copyOf(body, Math.min(body.length, 1024)));
        // Only the precise failure_key duplicate is folded; no INSERT IGNORE hiding bad data.
        jdbc.update(
                "INSERT INTO"
                    + " failure_record(id,failure_key,job_id,message_id,category,summary,ingress,fingerprint,body_sample)"
                    + " VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE failure_key=failure_key",
                bytes(id),
                key,
                bytes(job),
                bytes(message),
                category,
                category,
                ingress,
                fingerprint,
                sample);
        UUID existing =
                jdbc.queryForObject(
                        "SELECT id FROM failure_record WHERE failure_key=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        key);
        if (!id.equals(existing)) return;
        String summary =
                json.write(Map.of("failureId", id, "category", category, "schemaVersion", 1));
        outbox.insert(
                UUID.randomUUID(), null, 0, "work.failed", "FAILURE_AUDIT", summary, jobs.now());
    }

    public void quarantine(String messageId, String ingress, byte[] body) {
        String key =
                "ingress/"
                        + Inputs.hash(
                                ingress
                                        + "\n"
                                        + messageId
                                        + "\n"
                                        + Base64.getEncoder().encodeToString(body));
        record(key, null, null, "INVALID_ENVELOPE", ingress, body);
    }
}
