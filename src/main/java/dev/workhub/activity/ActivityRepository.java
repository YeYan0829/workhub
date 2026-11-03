package dev.workhub.activity;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.ActorContext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.util.*;

@Repository
public class ActivityRepository {
    private final JdbcTemplate jdbc;
    private final Json json;

    public ActivityRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long insert(
            UUID project,
            UUID task,
            ActorContext actor,
            UUID cause,
            String kind,
            Object before,
            Object after) {
        var key = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    var p =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " activity(project_id,task_id,fact_key,kind,actor_type,actor_id,cause_id,before_state,after_state)"
                                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    p.setBytes(1, bytes(project));
                    p.setBytes(2, bytes(task));
                    p.setString(3, cause + "/" + task + "/" + kind);
                    p.setString(4, kind);
                    p.setString(5, actor.type());
                    p.setBytes(
                            6, actor.type().equals("GITHUB_SYNC") ? null : bytes(actor.userId()));
                    p.setBytes(7, bytes(cause));
                    p.setString(8, json.write(before));
                    p.setString(9, json.write(after));
                    return p;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public void notify(UUID project, long activity, UUID user, Set<String> reasons) {
        jdbc.update(
                "INSERT INTO notification(project_id,activity_id,recipient_id,reason_set) VALUES"
                        + " (?,?,?,?)",
                bytes(project),
                activity,
                bytes(user),
                json.write(reasons));
    }

    public record Activity(
            long id,
            UUID taskId,
            String kind,
            String actorType,
            UUID actorId,
            String beforeState,
            String afterState,
            Instant createdAt) {}

    public record Notification(
            long id,
            long activityId,
            String reasonSet,
            Instant readAt,
            UUID taskId,
            String taskTitle,
            String kind,
            Instant createdAt) {}

    public List<Activity> list(UUID project, long before, int limit) {
        return jdbc.query(
                "SELECT * FROM activity WHERE project_id=? AND id<? ORDER BY id DESC LIMIT ?",
                (r, n) ->
                        new Activity(
                                r.getLong("id"),
                                read(r, "task_id"),
                                r.getString("kind"),
                                r.getString("actor_type"),
                                read(r, "actor_id"),
                                r.getString("before_state"),
                                r.getString("after_state"),
                                dev.workhub.common.Utc.read(r, "created_at")),
                bytes(project),
                before,
                limit);
    }

    public List<Notification> notifications(UUID project, UUID recipient, long before, int limit) {
        return jdbc.query(
                "SELECT n.*,a.task_id,a.kind,a.created_at,t.title task_title FROM notification n"
                    + " JOIN activity a ON a.id=n.activity_id AND a.project_id=n.project_id JOIN"
                    + " task t ON t.id=a.task_id AND t.project_id=n.project_id WHERE n.project_id=?"
                    + " AND n.recipient_id=? AND n.id<? ORDER BY n.id DESC LIMIT ?",
                (r, n) ->
                        new Notification(
                                r.getLong("id"),
                                r.getLong("activity_id"),
                                r.getString("reason_set"),
                                dev.workhub.common.Utc.read(r, "read_at"),
                                read(r, "task_id"),
                                r.getString("task_title"),
                                r.getString("kind"),
                                dev.workhub.common.Utc.read(r, "created_at")),
                bytes(project),
                bytes(recipient),
                before,
                limit);
    }

    public record InboxItem(
            long id,
            UUID taskId,
            String taskTitle,
            String kind,
            String reasonSet,
            Instant createdAt,
            Instant readAt,
            UUID projectId,
            String projectName,
            UUID workspaceId,
            String workspaceName,
            boolean archived) {}

    public record InboxPage(List<InboxItem> items, boolean hasMore, Long nextBefore) {}

    private static final String PERSONAL_FROM =
            " FROM notification n JOIN project p ON p.id=n.project_id JOIN workspace w ON"
                + " w.id=p.workspace_id JOIN project_member pm ON pm.project_id=p.id AND"
                + " pm.user_id=n.recipient_id JOIN workspace_member wm ON wm.workspace_id=w.id AND"
                + " wm.user_id=n.recipient_id JOIN app_user u ON u.id=n.recipient_id ";
    private static final String PERSONAL_WHERE =
            " WHERE n.recipient_id=? AND pm.active AND wm.active AND u.active ";

    public InboxPage inbox(
            UUID user, UUID workspace, UUID project, boolean unread, long before, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(bytes(user));
        String where = PERSONAL_WHERE;
        if (workspace != null) {
            where += " AND w.id=?";
            args.add(bytes(workspace));
        }
        if (project != null) {
            where += " AND p.id=?";
            args.add(bytes(project));
        }
        if (unread) where += " AND n.read_at IS NULL";
        where += " AND n.id<? ORDER BY n.id DESC LIMIT ?";
        args.add(before);
        args.add(limit + 1);
        var rows =
                jdbc.query(
                        "SELECT"
                            + " n.id,n.reason_set,n.read_at,a.task_id,a.kind,a.created_at,t.title,p.id"
                            + " project_id,p.name project_name,w.id workspace_id,w.name"
                            + " workspace_name,p.archived"
                                + PERSONAL_FROM
                                + " JOIN activity a ON a.id=n.activity_id AND a.project_id=p.id"
                                + " JOIN task t ON t.id=a.task_id AND t.project_id=p.id "
                                + where,
                        (r, n) ->
                                new InboxItem(
                                        r.getLong("id"),
                                        read(r, "task_id"),
                                        r.getString("title"),
                                        r.getString("kind"),
                                        r.getString("reason_set"),
                                        Utc.read(r, "created_at"),
                                        Utc.read(r, "read_at"),
                                        read(r, "project_id"),
                                        r.getString("project_name"),
                                        read(r, "workspace_id"),
                                        r.getString("workspace_name"),
                                        r.getBoolean("archived")),
                        args.toArray());
        boolean more = rows.size() > limit;
        var page = rows.stream().limit(limit).toList();
        return new InboxPage(page, more, more ? page.get(page.size() - 1).id() : null);
    }

    public long unreadCount(UUID user) {
        return jdbc.queryForObject(
                "SELECT COUNT(*)" + PERSONAL_FROM + PERSONAL_WHERE + " AND n.read_at IS NULL",
                Long.class,
                bytes(user));
    }

    public UUID notificationProject(UUID user, long id) {
        return jdbc
                .query(
                        "SELECT p.id" + PERSONAL_FROM + PERSONAL_WHERE + " AND n.id=?",
                        (r, n) -> read(r, "id"),
                        bytes(user),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public int markRead(UUID project, UUID user, long id) {
        return jdbc.update(
                "UPDATE notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6)) WHERE"
                        + " project_id=? AND recipient_id=? AND id=?",
                bytes(project),
                bytes(user),
                id);
    }
}
