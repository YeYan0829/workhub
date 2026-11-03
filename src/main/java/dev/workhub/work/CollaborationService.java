package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.activity.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.integration.github.GitHubService;
import dev.workhub.onboarding.Workspaces;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Service
public class CollaborationService {
    private final JdbcTemplate db;
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final Workspaces spaces;
    private final TaskRepository tasks;
    private final ContentService content;
    private final ActivityService activity;
    private final GitHubService github;
    private final TaskReassignmentRepository reassignments;

    public CollaborationService(
            JdbcTemplate db,
            ProjectAccess access,
            ProjectRepository projects,
            Workspaces spaces,
            TaskRepository tasks,
            ContentService content,
            ActivityService activity,
            GitHubService github,
            TaskReassignmentRepository reassignments) {
        this.db = db;
        this.access = access;
        this.projects = projects;
        this.spaces = spaces;
        this.tasks = tasks;
        this.content = content;
        this.activity = activity;
        this.github = github;
        this.reassignments = reassignments;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Project project(
            ActorContext a,
            UUID p,
            String name,
            String description,
            boolean archived,
            long expected) {
        var old = access.lockManagement(a, p);
        access.maintainer(a, p);
        access.expected(old, expected);
        Inputs.text(name, "name", 200);
        if (description == null || description.length() > 10000)
            throw ApiException.invalid("项目描述最长10000字符");
        db.update(
                "UPDATE project SET"
                    + " name=?,description=?,archived=?,business_revision=business_revision+1 WHERE"
                    + " id=?",
                name,
                description,
                archived,
                bytes(p));
        spaces.audit(
                a,
                old.workspaceId(),
                p,
                archived ? "PROJECT_ARCHIVED" : "PROJECT_UPDATED",
                Map.of("name", name, "archived", archived));
        return projects.find(p).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> summary(ActorContext a, UUID p) {
        access.read(a, p);
        var counts = new LinkedHashMap<String, Long>();
        for (var status : TaskStatus.values()) counts.put(status.name(), 0L);
        db.query("SELECT status,COUNT(*) total FROM task WHERE project_id=? GROUP BY status",
                r -> { counts.put(r.getString("status"), r.getLong("total")); }, bytes(p));
        return counts;
    }

    public List<Workspaces.Member> members(ActorContext a, UUID p) {
        access.read(a, p);
        return db.query(
                "SELECT u.id,u.login,u.display_name,u.email,m.role,m.active AND wm.active AND"
                    + " u.active valid FROM project_member m JOIN app_user u ON u.id=m.user_id JOIN"
                    + " project p ON p.id=m.project_id JOIN workspace_member wm ON"
                    + " wm.workspace_id=p.workspace_id AND wm.user_id=m.user_id WHERE"
                    + " m.project_id=? ORDER BY u.login LIMIT 1000",
                (r, n) ->
                        new Workspaces.Member(
                                read(r, "id"),
                                r.getString("login"),
                                r.getString("display_name"),
                                r.getString("email"),
                                r.getString("role"),
                                r.getBoolean("valid")),
                bytes(p));
    }

    public record ProjectContext(
            UUID id, String name, UUID workspaceId, String workspaceName, boolean archived) {}

    public record Page(
            List<TaskResponse> items,
            int offset,
            boolean hasMore,
            Map<UUID, ProjectContext> projects) {}

    public List<ProjectContext> accessibleProjects(ActorContext a) {
        return db.query(
                "SELECT p.id,p.name,p.workspace_id,w.name workspace_name,p.archived FROM project p"
                    + " JOIN workspace w ON w.id=p.workspace_id JOIN project_member pm ON"
                    + " pm.project_id=p.id JOIN workspace_member wm ON wm.workspace_id=w.id AND"
                    + " wm.user_id=pm.user_id JOIN app_user u ON u.id=pm.user_id WHERE pm.user_id=?"
                    + " AND pm.active AND wm.active AND u.active ORDER BY w.name,w.id,p.name,p.id"
                    + " LIMIT 1000",
                (r, n) ->
                        new ProjectContext(
                                read(r, "id"),
                                r.getString("name"),
                                read(r, "workspace_id"),
                                r.getString("workspace_name"),
                                r.getBoolean("archived")),
                bytes(a.userId()));
    }

    public Page query(
            ActorContext a,
            UUID p,
            String q,
            String status,
            String assignee,
            Boolean blocked,
            LocalDate due,
            int offset,
            int limit) {
        return query(a, p, q, status, assignee, blocked, due, offset, limit, null, null);
    }

    public Page query(
            ActorContext a,
            UUID p,
            String q,
            String status,
            String assignee,
            Boolean blocked,
            LocalDate due,
            int offset,
            int limit,
            UUID workspaceId,
            UUID projectId) {
        if (p != null) access.read(a, p);
        if (offset < 0 || offset > 100000) throw ApiException.invalid("Invalid offset");
        limit = Inputs.limit(limit);
        if (q != null && q.length() > 200) throw ApiException.invalid("查询过长");
        List<Object> args = new ArrayList<>();
        String sql =
                "SELECT t.id,t.project_id,p.name project_name,p.workspace_id,w.name"
                    + " workspace_name,p.archived FROM task t JOIN project p ON p.id=t.project_id"
                    + " JOIN workspace w ON w.id=p.workspace_id JOIN project_member pm ON"
                    + " pm.project_id=p.id JOIN workspace_member wm ON"
                    + " wm.workspace_id=p.workspace_id AND wm.user_id=pm.user_id JOIN app_user u ON"
                    + " u.id=pm.user_id WHERE pm.user_id=? AND pm.active AND wm.active AND"
                    + " u.active";
        args.add(bytes(a.userId()));
        if (p == null) {
            sql += " AND t.assignee_id=?";
            args.add(bytes(a.userId()));
        } else {
            sql += " AND p.id=?";
            args.add(bytes(p));
        }
        if (workspaceId != null) {
            sql += " AND p.workspace_id=?";
            args.add(bytes(workspaceId));
        }
        if (projectId != null) {
            sql += " AND p.id=?";
            args.add(bytes(projectId));
        }
        if (q != null && !q.isBlank()) {
            sql += " AND LOCATE(?,t.title)>0";
            args.add(q);
        }
        if (status != null && !status.isBlank()) {
            try {
                TaskStatus.valueOf(status);
            } catch (Exception e) {
                throw ApiException.invalid("Invalid status");
            }
            sql += " AND t.status=?";
            args.add(status);
        }
        if (assignee != null && !assignee.isBlank()) {
            if (assignee.equals("unassigned")) sql += " AND t.assignee_id IS NULL";
            else if (assignee.equals("inactive"))
                sql +=
                        " AND t.assignee_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM project_member"
                                + " x JOIN workspace_member y ON y.workspace_id=p.workspace_id AND"
                                + " y.user_id=x.user_id JOIN app_user z ON z.id=x.user_id WHERE"
                                + " x.project_id=p.id AND x.user_id=t.assignee_id AND x.active AND"
                                + " y.active AND z.active)";
            else {
                sql += " AND t.assignee_id=?";
                try {
                    args.add(bytes(UUID.fromString(assignee)));
                } catch (Exception e) {
                    throw ApiException.invalid("Invalid assignee");
                }
            }
        }
        if (blocked != null) {
            sql +=
                    " AND EXISTS(SELECT 1 FROM task_dependency d JOIN task b ON b.id=d.blocker_id"
                            + " WHERE d.project_id=t.project_id AND d.blocked_id=t.id AND"
                            + " b.status<>'DONE')=?";
            args.add(blocked);
        }
        if (due != null) {
            sql += " AND t.due_date<=?";
            args.add(due);
        }
        sql += " ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?";
        args.add(limit + 1);
        args.add(offset);
        Map<UUID, ProjectContext> context = new LinkedHashMap<>();
        var ids =
                db.query(
                        sql,
                        (r, n) -> {
                            UUID project = read(r, "project_id");
                            context.put(
                                    project,
                                    new ProjectContext(
                                            project,
                                            r.getString("project_name"),
                                            read(r, "workspace_id"),
                                            r.getString("workspace_name"),
                                            r.getBoolean("archived")));
                            return new UUID[] {project, read(r, "id")};
                        },
                        args.toArray());
        boolean more = ids.size() > limit;
        return new Page(
                ids.stream()
                        .limit(limit)
                        .map(x -> TaskResponse.from(tasks.find(x[0], x[1]).orElseThrow()))
                        .toList(),
                offset,
                more,
                context);
    }

    public Map<String, Object> detail(ActorContext a, UUID p, UUID t) {
        var project = access.read(a, p);
        var task = tasks.find(p, t).orElseThrow(ApiException::missing);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", TaskResponse.from(task));
        result.put("createdBy", task.createdBy());
        result.put("canEditTask", !project.archived() && access.canEditTask(a, task));
        result.put("canManageTask", !project.archived() && access.isMaintainer(a, p));
        result.put("canManageDependencies", !project.archived() && access.canEditTask(a, task));
        result.put("canPrepareTask", !project.archived() && access.canPrepareTask(a, task));
        result.put("canRequestReassignment", !project.archived() && access.canExecuteTask(a, task));
        result.put("reassignmentRequests", reassignments.list(p, t));
        result.put("members", members(a, p));
        result.put(
                "subscribed",
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM task_subscription WHERE project_id=?"
                                        + " AND task_id=? AND user_id=? AND active)",
                                Boolean.class,
                                bytes(p),
                                bytes(t),
                                bytes(a.userId()))));
        result.put("blockers", tasks.blockers(p, t).stream().map(TaskResponse::from).toList());
        result.put("downstream", tasks.downstream(p, t).stream().map(TaskResponse::from).toList());
        result.put("links", content.links(a, p, t));
        result.put("comments", content.comments(a, p, t, null, 50));
        result.put(
                "activities",
                db.queryForList(
                        "SELECT id,kind,actor_type actorType,BIN_TO_UUID(actor_id)"
                            + " actorId,created_at createdAt FROM activity WHERE project_id=? AND"
                            + " task_id=? ORDER BY id DESC LIMIT 50",
                        bytes(p),
                        bytes(t)));
        if (task.origin() == TaskOrigin.GITHUB) {
            result.put("sync", github.view(a, p, t));
            result.put(
                    "externalComments",
                    db.queryForList(
                            "SELECT c.remote_id remoteId,c.body,c.remote_login"
                                + " remoteLogin,c.remote_updated_at updatedAt FROM github_comment c"
                                + " JOIN github_issue i ON i.resource_id=c.resource_id WHERE"
                                + " i.project_id=? AND i.task_id=? AND c.active ORDER BY"
                                + " c.remote_id LIMIT 50",
                            bytes(p),
                            bytes(t)));
        }
        return result;
    }
}
