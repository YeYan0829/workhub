package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT =
            """
SELECT t.*, EXISTS(SELECT 1 FROM task_dependency d
JOIN task a ON a.project_id=d.project_id AND a.id=d.blocker_id
WHERE d.project_id=t.project_id AND d.blocked_id=t.id AND a.status<>'DONE') AS is_blocked
FROM task t
""";
    private final RowMapper<Task> mapper =
            (r, n) ->
                    new Task(
                            read(r, "id"),
                            read(r, "project_id"),
                            r.getString("title"),
                            r.getString("description"),
                            TaskStatus.valueOf(r.getString("status")),
                            TaskType.valueOf(r.getString("type")),
                            r.getInt("priority"),
                            read(r, "assignee_id"),
                            r.getObject("due_date", LocalDate.class),
                            TaskOrigin.valueOf(r.getString("origin")),
                            r.getLong("revision"),
                            r.getLong("discussion_revision"),
                            read(r, "created_by"),
                            dev.workhub.common.Utc.read(r, "created_at"),
                            dev.workhub.common.Utc.read(r, "updated_at"),
                            r.getBoolean("is_blocked"),
                            r.getBoolean("triage_required"));

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Task> find(UUID project, UUID id) {
        return jdbc
                .query(
                        SELECT + " WHERE t.project_id=? AND t.id=?",
                        mapper,
                        bytes(project),
                        bytes(id))
                .stream()
                .findFirst();
    }

    public record Cursor(Instant updatedAt, UUID id) {}

    public List<Task> list(
            UUID project,
            TaskStatus status,
            UUID assignee,
            Integer priority,
            Cursor cursor,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE t.project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(project));
        if (status != null) {
            sql.append(" AND t.status=?");
            args.add(status.name());
        }
        if (assignee != null) {
            sql.append(" AND t.assignee_id=?");
            args.add(bytes(assignee));
        }
        if (priority != null) {
            sql.append(" AND t.priority=?");
            args.add(priority);
        }
        if (cursor != null) {
            sql.append(" AND (t.updated_at<? OR (t.updated_at=? AND t.id<?))");
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(bytes(cursor.id()));
        }
        sql.append(" ORDER BY t.updated_at DESC,t.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public void insert(
            UUID project,
            UUID id,
            String title,
            String description,
            TaskStatus status,
            TaskType type,
            int priority,
            UUID assignee,
            LocalDate dueDate,
            UUID creator) {
        jdbc.update(
                """
INSERT INTO task(id,project_id,title,description,status,type,priority,assignee_id,due_date,origin,created_by)
VALUES (?,?,?,?,?,?,?,?,?,'MANUAL',?)
""",
                bytes(id),
                bytes(project),
                title,
                description,
                status.name(),
                type.name(),
                priority,
                bytes(assignee),
                dueDate,
                bytes(creator));
    }

    public void insertGitHub(
            UUID project, UUID id, String title, String body, TaskStatus status, UUID creator) {
        jdbc.update(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,?,?,?,'TASK','GITHUB',?)",
                bytes(id),
                bytes(project),
                title,
                body,
                status.name(),
                bytes(creator));
    }

    public int update(
            Task old,
            String title,
            String description,
            TaskStatus status,
            TaskType type,
            int priority,
            UUID assignee,
            LocalDate dueDate,
            long expected) {
        return jdbc.update(
                """
UPDATE task SET title=?,description=?,status=?,type=?,priority=?,assignee_id=?,due_date=?,
revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE project_id=? AND id=? AND revision=?
""",
                title,
                description,
                status.name(),
                type.name(),
                priority,
                bytes(assignee),
                dueDate,
                bytes(old.projectId()),
                bytes(old.id()),
                expected);
    }

    public List<Task> downstream(UUID project, UUID blocker) {
        return jdbc.query(
                SELECT
                        + " JOIN task_dependency outgoing ON outgoing.project_id=t.project_id AND"
                        + " outgoing.blocked_id=t.id WHERE outgoing.project_id=? AND"
                        + " outgoing.blocker_id=?",
                mapper,
                bytes(project),
                bytes(blocker));
    }

    public List<Task> blockers(UUID project, UUID blocked) {
        return jdbc.query(
                SELECT
                        + " JOIN task_dependency incoming ON incoming.project_id=t.project_id AND"
                        + " incoming.blocker_id=t.id WHERE incoming.project_id=? AND"
                        + " incoming.blocked_id=?",
                mapper,
                bytes(project),
                bytes(blocked));
    }

    public boolean triageRequired(UUID id) {
        return jdbc.queryForObject(
                "SELECT triage_required FROM task WHERE id=?", Boolean.class, bytes(id));
    }

    public void triageRequired(UUID id, boolean value) {
        jdbc.update("UPDATE task SET triage_required=? WHERE id=?", value, bytes(id));
    }

    public void discussionChanged(UUID project, UUID task) {
        jdbc.update(
                "UPDATE task SET discussion_revision=discussion_revision+1 WHERE project_id=? AND"
                        + " id=?",
                bytes(project),
                bytes(task));
    }
}
