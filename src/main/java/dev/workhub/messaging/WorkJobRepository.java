package dev.workhub.messaging;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Utc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class WorkJobRepository {
    public record Job(
            UUID id,
            UUID project,
            UUID owner,
            String kind,
            String handler,
            String payload,
            String payloadHash,
            String status,
            long sequence,
            int attempt,
            Instant nextAttempt) {}

    private final JdbcTemplate jdbc;

    public WorkJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Job find(UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM work_job WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Job(
                                        read(r, "id"),
                                        read(r, "project_id"),
                                        read(r, "owner_ref"),
                                        r.getString("kind"),
                                        r.getString("handler"),
                                        r.getString("payload"),
                                        r.getString("payload_hash"),
                                        r.getString("status"),
                                        r.getLong("dispatch_seq"),
                                        r.getInt("attempt"),
                                        Utc.read(r, "next_attempt_at")),
                        bytes(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void lockProject(Job job) {
        if (job.project() != null) {
            jdbc.queryForList(
                    "SELECT w.id FROM workspace w WHERE w.id=(SELECT workspace_id FROM project"
                            + " WHERE id=?) FOR SHARE",
                    bytes(job.project()));
            jdbc.queryForList(
                    "SELECT id FROM project WHERE id=?"
                            + ("CSV_ROW".equals(job.handler()) ? " FOR UPDATE" : " FOR SHARE"),
                    bytes(job.project()));
        }
    }

    public Instant now() {
        return jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(6)", (r, n) -> r.getTimestamp(1).toInstant());
    }

    public boolean claimExecution(Job job, UUID token, int seconds) {
        return jdbc.update(
                        "UPDATE work_job SET"
                            + " status='RUNNING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))"
                            + " WHERE id=? AND (status IN ('WAITING','RETRY_WAIT') OR"
                            + " (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))",
                        bytes(token),
                        seconds,
                        bytes(job.id()))
                == 1;
    }

    public boolean ownsExecution(UUID id, UUID token) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_job WHERE id=? AND status='RUNNING' AND"
                                + " lease_token=? AND lease_until>UTC_TIMESTAMP(6)",
                        Integer.class,
                        bytes(id),
                        bytes(token))
                == 1;
    }

    public void done(Job job, WorkHandler.Result result) {
        jdbc.update(
                "UPDATE work_job SET"
                    + " status='DONE',lease_token=NULL,lease_until=NULL,attempt=attempt+1,completed_at=UTC_TIMESTAMP(6),last_progress_at=UTC_TIMESTAMP(6),result_ref=?,result_hash=?,last_error=NULL"
                    + " WHERE id=?",
                result.reference(),
                result.hash(),
                bytes(job.id()));
    }

    public void retry(Job job, long sequence, Instant due, String error, boolean attempted) {
        jdbc.update(
                "UPDATE work_job SET"
                    + " status='RETRY_WAIT',lease_token=NULL,lease_until=NULL,dispatch_seq=?,attempt=attempt+?,next_attempt_at=?,last_progress_at=UTC_TIMESTAMP(6),last_error=?"
                    + " WHERE id=?",
                sequence,
                attempted ? 1 : 0,
                Utc.value(due),
                error,
                bytes(job.id()));
    }

    public void failed(Job job, String error) {
        jdbc.update(
                "UPDATE work_job SET"
                    + " status='FAILED',attempt=attempt+1,last_error=?,last_progress_at=UTC_TIMESTAMP(6),completed_at=UTC_TIMESTAMP(6)"
                    + " WHERE id=?",
                error,
                bytes(job.id()));
    }

    public List<UUID> unfinished(UUID after, int limit) {
        return jdbc.query(
                "SELECT id FROM work_job WHERE status IN ('WAITING','RETRY_WAIT') AND id>? ORDER BY"
                        + " id LIMIT ?",
                (r, n) -> uuid(r.getBytes(1)),
                after == null ? new byte[16] : bytes(after),
                limit);
    }
}
