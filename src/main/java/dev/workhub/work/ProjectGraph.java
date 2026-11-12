package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.identity.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

/** A bounded, consistent project snapshot for the overview. Task details load on selection. */
@Service
public class ProjectGraph {
    static final int MAX_NODES = 200;
    static final int MAX_EDGES = 1000;
    private final JdbcTemplate db;
    private final ProjectAccess access;

    public ProjectGraph(JdbcTemplate db, ProjectAccess access) {
        this.db = db;
        this.access = access;
    }

    public record Node(
            UUID id, String title, TaskStatus status, TaskType type, int priority,
            UUID assigneeId, LocalDate dueDate, TaskOrigin origin, boolean triageRequired,
            boolean isBlocked, boolean dependencyRisk, int upstreamCount, int downstreamCount) {}

    public record Snapshot(
            long businessRevision, Map<String, Long> counts, long totalTasks, long totalEdges,
            boolean partial, List<Node> nodes, List<Dependency> edges) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(ActorContext actor, UUID project) {
        var current = access.read(actor, project);
        var counts = new LinkedHashMap<String, Long>();
        for (var status : TaskStatus.values()) counts.put(status.name(), 0L);
        db.query("SELECT status,COUNT(*) total FROM task WHERE project_id=? GROUP BY status",
                r -> { counts.put(r.getString("status"), r.getLong("total")); }, bytes(project));
        long totalTasks = counts.values().stream().mapToLong(Long::longValue).sum();
        long totalEdges = db.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE project_id=?", Long.class, bytes(project));
        var nodes = db.query("""
                SELECT t.id,t.title,t.status,t.type,t.priority,t.assignee_id,t.due_date,t.origin,
                       t.triage_required,
                       EXISTS(SELECT 1 FROM task_dependency d JOIN task b
                         ON b.project_id=d.project_id AND b.id=d.blocker_id
                         WHERE d.project_id=t.project_id AND d.blocked_id=t.id
                         AND b.status<>'DONE') is_blocked,
                       (SELECT COUNT(*) FROM task_dependency d
                         WHERE d.project_id=t.project_id AND d.blocked_id=t.id) upstream_count,
                       (SELECT COUNT(*) FROM task_dependency d
                         WHERE d.project_id=t.project_id AND d.blocker_id=t.id) downstream_count
                FROM task t WHERE t.project_id=?
                ORDER BY (t.status IN ('DONE','CANCELED')),t.created_at,t.id LIMIT ?
                """, (r, n) -> {
                    var status = TaskStatus.valueOf(r.getString("status"));
                    boolean blocked = r.getBoolean("is_blocked");
                    return new Node(read(r, "id"), r.getString("title"), status,
                            TaskType.valueOf(r.getString("type")), r.getInt("priority"),
                            read(r, "assignee_id"), r.getObject("due_date", LocalDate.class),
                            TaskOrigin.valueOf(r.getString("origin")), r.getBoolean("triage_required"),
                            blocked, TaskSemantics.dependencyRisk(status, blocked),
                            r.getInt("upstream_count"), r.getInt("downstream_count"));
                }, bytes(project), MAX_NODES);
        List<Dependency> edges = List.of();
        if (!nodes.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(nodes.size(), "?"));
            var args = new ArrayList<Object>();
            args.add(bytes(project));
            for (var node : nodes) args.add(bytes(node.id()));
            for (var node : nodes) args.add(bytes(node.id()));
            args.add(MAX_EDGES);
            edges = db.query("SELECT blocker_id,blocked_id FROM task_dependency WHERE project_id=?"
                            + " AND blocker_id IN (" + placeholders + ") AND blocked_id IN ("
                            + placeholders + ") ORDER BY blocker_id,blocked_id LIMIT ?",
                    (r, n) -> new Dependency(read(r, "blocker_id"), read(r, "blocked_id")),
                    args.toArray());
        }
        return new Snapshot(current.businessRevision(), counts, totalTasks, totalEdges,
                nodes.size() < totalTasks || edges.size() < totalEdges, nodes, edges);
    }
}
