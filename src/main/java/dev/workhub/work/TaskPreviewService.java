package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
public class TaskPreviewService {
    private final JdbcTemplate db;
    private final ProjectAccess access;

    public TaskPreviewService(JdbcTemplate db, ProjectAccess access) {
        this.db = db;
        this.access = access;
    }

    public record Task(
            UUID id,
            String title,
            String description,
            boolean descriptionTruncated,
            String status,
            String type,
            int priority,
            UUID assigneeId,
            GraphReadService.Person assignee,
            LocalDate dueDate,
            String origin,
            boolean triageRequired,
            long revision,
            long discussionRevision,
            boolean isBlocked,
            boolean dependencyRisk,
            String href) {}

    public record Comment(
            UUID id,
            UUID authorId,
            String authorDisplayName,
            String body,
            boolean truncated,
            long revision,
            Instant createdAt,
            String href) {}

    public record Preview(Task task, List<Comment> comments, boolean moreComments) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(ActorContext actor, UUID project, UUID task, int limit) {
        access.read(actor, project);
        if (limit < 1 || limit > 5) throw ApiException.invalid("commentLimit must be 1..5");
        String href = "#task/" + project + "/" + task;
        var value =
                db
                        .query(
                                """
SELECT t.id,t.title,LEFT(t.description,2000) description,CHAR_LENGTH(t.description)>2000 truncated,
t.status,t.type,t.priority,t.assignee_id,u.display_name,(u.active AND pm.active AND wm.active) active,t.due_date,t.origin,t.triage_required,
t.revision,t.discussion_revision,EXISTS(SELECT 1 FROM task_dependency d JOIN task b ON b.id=d.blocker_id
AND b.project_id=d.project_id WHERE d.project_id=t.project_id AND d.blocked_id=t.id AND b.status<>'DONE') blocked
FROM task t LEFT JOIN app_user u ON u.id=t.assignee_id
LEFT JOIN project p ON p.id=t.project_id LEFT JOIN project_member pm ON pm.project_id=t.project_id AND pm.user_id=t.assignee_id
LEFT JOIN workspace_member wm ON wm.workspace_id=p.workspace_id AND wm.user_id=t.assignee_id WHERE t.project_id=? AND t.id=?
""",
                                (r, n) -> {
                                    UUID owner = read(r, "assignee_id");
                                    String status = r.getString("status");
                                    boolean blocked = r.getBoolean("blocked");
                                    return new Task(
                                            read(r, "id"),
                                            r.getString("title"),
                                            r.getString("description"),
                                            r.getBoolean("truncated"),
                                            status,
                                            r.getString("type"),
                                            r.getInt("priority"),
                                            owner,
                                            owner == null
                                                    ? null
                                                    : new GraphReadService.Person(
                                                            owner,
                                                            r.getString("display_name"),
                                                            r.getBoolean("active")),
                                            r.getObject("due_date", LocalDate.class),
                                            r.getString("origin"),
                                            r.getBoolean("triage_required"),
                                            r.getLong("revision"),
                                            r.getLong("discussion_revision"),
                                            blocked,
                                            TaskSemantics.dependencyRisk(
                                                    TaskStatus.valueOf(status), blocked),
                                            href);
                                },
                                bytes(project),
                                bytes(task))
                        .stream()
                        .findFirst()
                        .orElseThrow(ApiException::missing);
        var comments =
                db.query(
                        """
SELECT c.id,c.author_id,u.display_name,LEFT(c.body,1000) body,CHAR_LENGTH(c.body)>1000 truncated,c.revision,c.created_at
FROM comment c JOIN app_user u ON u.id=c.author_id WHERE c.project_id=? AND c.task_id=? AND NOT c.deleted
ORDER BY c.created_at DESC,c.id DESC LIMIT ?
""",
                        (r, n) ->
                                new Comment(
                                        read(r, "id"),
                                        read(r, "author_id"),
                                        r.getString("display_name"),
                                        r.getString("body"),
                                        r.getBoolean("truncated"),
                                        r.getLong("revision"),
                                        Utc.read(r, "created_at"),
                                        href + "?comment=" + read(r, "id")),
                        bytes(project),
                        bytes(task),
                        limit + 1);
        return new Preview(
                value,
                List.copyOf(comments.subList(0, Math.min(limit, comments.size()))),
                comments.size() > limit);
    }
}
