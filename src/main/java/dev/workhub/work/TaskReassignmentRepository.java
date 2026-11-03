package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Utc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class TaskReassignmentRepository {
    public record Request(
            UUID id,
            UUID projectId,
            UUID taskId,
            UUID requestedBy,
            String reason,
            String status,
            long revision,
            UUID reviewedBy,
            String decisionNote,
            Instant createdAt,
            Instant resolvedAt) {}

    private final JdbcTemplate db;

    public TaskReassignmentRepository(JdbcTemplate db) {
        this.db = db;
    }

    private Request row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Request(
                read(r, "id"),
                read(r, "project_id"),
                read(r, "task_id"),
                read(r, "requested_by"),
                r.getString("reason"),
                r.getString("status"),
                r.getLong("revision"),
                read(r, "reviewed_by"),
                r.getString("decision_note"),
                Utc.read(r, "created_at"),
                Utc.read(r, "resolved_at"));
    }

    public Optional<Request> find(UUID project, UUID task, UUID id) {
        return db
                .query(
                        "SELECT * FROM task_reassignment_request WHERE project_id=? AND task_id=?"
                            + " AND id=?",
                        (r, n) -> row(r),
                        bytes(project),
                        bytes(task),
                        bytes(id))
                .stream()
                .findFirst();
    }

    public List<Request> list(UUID project, UUID task) {
        return db.query(
                "SELECT * FROM task_reassignment_request WHERE project_id=? AND task_id=? ORDER BY"
                    + " created_at DESC,id DESC LIMIT 20",
                (r, n) -> row(r),
                bytes(project),
                bytes(task));
    }

    public boolean pending(UUID project, UUID task) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM task_reassignment_request WHERE project_id=?"
                            + " AND task_id=? AND status='PENDING')",
                        Boolean.class,
                        bytes(project),
                        bytes(task)));
    }

    public void insert(UUID id, UUID project, UUID task, UUID actor, String reason) {
        db.update(
                "INSERT INTO task_reassignment_request(id,project_id,task_id,requested_by,reason)"
                    + " VALUES (?,?,?,?,?)",
                bytes(id),
                bytes(project),
                bytes(task),
                bytes(actor),
                reason);
    }

    public int decide(Request request, UUID actor, String status, String note) {
        return db.update(
                "UPDATE task_reassignment_request SET"
                    + " status=?,reviewed_by=?,decision_note=?,revision=revision+1,resolved_at=UTC_TIMESTAMP(6)"
                    + " WHERE id=? AND revision=? AND status='PENDING'",
                status,
                bytes(actor),
                note,
                bytes(request.id()),
                request.revision());
    }

    public void supersede(UUID project, UUID task) {
        db.update(
                "UPDATE task_reassignment_request SET"
                    + " status='SUPERSEDED',revision=revision+1,resolved_at=UTC_TIMESTAMP(6) WHERE"
                    + " project_id=? AND task_id=? AND status='PENDING'",
                bytes(project),
                bytes(task));
    }
}
