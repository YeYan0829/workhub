package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Bounded SQL projections. Every public read uses one repeatable-read snapshot. */
@Service
public class GraphReadService {
    public static final int MAX_NODES = 200, MAX_EDGES = 1000;
    private final JdbcTemplate db;
    private final ProjectAccess access;
    private final OverviewState states;

    public GraphReadService(JdbcTemplate db, ProjectAccess access, OverviewState states) {
        this.db = db;
        this.access = access;
        this.states = states;
    }

    public record Person(UUID id, String displayName, boolean active) {}

    public record Node(
            UUID id,
            String title,
            boolean titleTruncated,
            TaskStatus status,
            TaskType type,
            int priority,
            UUID assigneeId,
            Person assignee,
            LocalDate dueDate,
            TaskOrigin origin,
            boolean triageRequired,
            boolean isBlocked,
            boolean dependencyRisk,
            long revision,
            long discussionRevision,
            int upstreamCount,
            int downstreamCount,
            Integer visibleUpstreamCount,
            Integer visibleDownstreamCount) {}

    public record Scope(
            String mode, int nodeLimit, int edgeLimit, boolean nodePartial, boolean edgePartial) {}

    public record Expansion(String nextCursor, boolean hasMore, List<UUID> addedNodeIds) {}

    public record Snapshot(
            String viewSeq,
            long businessRevision,
            String topologyRevision,
            Map<String, Long> counts,
            long totalTasks,
            long totalEdges,
            boolean partial,
            Scope scope,
            List<Node> nodes,
            List<Dependency> edges,
            Expansion expansion) {}

    public record Expand(UUID taskId, String direction, String cursor, Integer limit) {}

    public record ViewRequest(List<UUID> nodeIds, Expand expand) {}

    public record SearchItem(UUID id, String title, String status) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> search(ActorContext actor, UUID project, String query, int limit) {
        access.read(actor, project);
        if (query == null || query.length() > 200 || limit < 1 || limit > 20)
            throw ApiException.invalid("Invalid graph search");
        var items =
                db.query(
                        "SELECT id,title,status FROM task WHERE project_id=? AND LOCATE(?,title)>0"
                                + " ORDER BY updated_at DESC,id DESC LIMIT ?",
                        (r, n) ->
                                new SearchItem(
                                        read(r, "id"), r.getString("title"), r.getString("status")),
                        bytes(project),
                        query,
                        limit + 1);
        return Map.of(
                "items",
                List.copyOf(items.subList(0, Math.min(limit, items.size()))),
                "hasMore",
                items.size() > limit);
    }

    static String marks(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    static ArrayList<Object> args(UUID project, Collection<UUID> ids) {
        var a = new ArrayList<Object>();
        a.add(bytes(project));
        ids.forEach(id -> a.add(bytes(id)));
        return a;
    }

    static LinkedHashSet<UUID> validate(List<UUID> ids) {
        if (ids == null || ids.size() > MAX_NODES || ids.stream().anyMatch(Objects::isNull))
            throw ApiException.invalid("nodeIds must contain at most 200 task IDs");
        return new LinkedHashSet<>(ids);
    }

    void requireIds(UUID project, Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        var count =
                db.queryForObject(
                        "SELECT COUNT(*) FROM task WHERE project_id=? AND id IN ("
                                + marks(ids.size())
                                + ")",
                        Long.class,
                        args(project, ids).toArray());
        if (count != ids.size())
            throw ApiException.invalid("Every node must belong to this project");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot initial(
            ActorContext actor,
            UUID project,
            String mode,
            UUID focus,
            Integer nodeLimit,
            Integer edgeLimit) {
        access.read(actor, project);
        int nl = nodeLimit == null ? 50 : nodeLimit, el = edgeLimit == null ? 150 : edgeLimit;
        if (nl < 1 || nl > MAX_NODES || el < 1 || el > MAX_EDGES)
            throw ApiException.invalid("Invalid graph budget");
        if (!Set.of("overview", "focus").contains(mode))
            throw ApiException.invalid("Unknown graph view");
        var current = states.read(project);
        var ids = new LinkedHashSet<UUID>();
        if (mode.equals("overview") && current.totalTasks() <= nl) {
            ids.addAll(
                    db.query(
                            "SELECT id FROM task WHERE project_id=? ORDER BY id LIMIT ?",
                            (r, n) -> read(r, "id"),
                            bytes(project),
                            nl));
            return snapshot(project, current, ids, mode, nl, el, null);
        }
        if (mode.equals("focus")) {
            if (focus == null) throw ApiException.invalid("focusTaskId is required");
            requireIds(project, List.of(focus));
            ids.add(focus);
        } else if (current.counts().get("IN_PROGRESS")
                        + current.counts().get("TODO")
                        + current.counts().get("TRIAGE")
                == 0) {
            ids.addAll(
                    db.query(
                            "SELECT id FROM task WHERE project_id=? ORDER BY updated_at DESC,id"
                                + " DESC LIMIT ?",
                            (r, n) -> read(r, "id"),
                            bytes(project),
                            Math.min(20, nl)));
        } else {
            String[] statuses = {"IN_PROGRESS", "TODO", "TRIAGE"};
            int[] quotas = {8, 8, 4};
            for (int i = 0; i < statuses.length && ids.size() < nl; i++)
                ids.addAll(
                        db.query(
                                "SELECT id FROM task WHERE project_id=? AND status=? ORDER BY"
                                        + " priority,updated_at DESC,id DESC LIMIT ?",
                                (r, n) -> read(r, "id"),
                                bytes(project),
                                statuses[i],
                                Math.min(quotas[i], nl - ids.size())));
            if (ids.size() < Math.min(20, nl)) {
                for (String status : new String[] {"IN_PROGRESS", "TODO", "TRIAGE"}) {
                    if (ids.size() >= Math.min(20, nl)) break;
                    var a = args(project, ids);
                    a.add(1, status);
                    a.add(Math.min(20, nl) - ids.size());
                    ids.addAll(
                            db.query(
                                    "SELECT id FROM task WHERE project_id=? AND status=?"
                                            + (ids.isEmpty()
                                                    ? ""
                                                    : " AND id NOT IN (" + marks(ids.size()) + ")")
                                            + " ORDER BY priority,updated_at DESC,id DESC LIMIT ?",
                                    (r, n) -> read(r, "id"),
                                    a.toArray()));
                }
            }
        }
        var seeds = new LinkedHashSet<>(ids);
        for (String direction : List.of("UPSTREAM", "DOWNSTREAM")) {
            if (ids.size() >= nl || seeds.isEmpty()) break;
            ids.addAll(neighbors(project, seeds, direction, null, nl - ids.size()));
        }
        return snapshot(project, current, ids, mode, nl, el, null);
    }

    List<UUID> neighbors(
            UUID project, Collection<UUID> ids, String direction, UUID after, int limit) {
        if (ids.isEmpty() || limit <= 0) return List.of();
        String match = direction.equals("UPSTREAM") ? "blocked_id" : "blocker_id",
                other = direction.equals("UPSTREAM") ? "blocker_id" : "blocked_id";
        var a = args(project, ids);
        if (after != null) a.add(bytes(after));
        a.add(limit);
        return db.query(
                "SELECT DISTINCT "
                        + other
                        + " id FROM task_dependency WHERE project_id=? AND "
                        + match
                        + " IN ("
                        + marks(ids.size())
                        + ")"
                        + (after == null ? "" : " AND " + other + ">?")
                        + " ORDER BY "
                        + other
                        + " LIMIT ?",
                (r, n) -> read(r, "id"),
                a.toArray());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot view(ActorContext actor, UUID project, ViewRequest request) {
        access.read(actor, project);
        if (request == null) throw ApiException.invalid("Missing view request");
        var ids = validate(request.nodeIds());
        requireIds(project, ids);
        var state = states.read(project);
        Expansion expansion = null;
        var e = request.expand();
        if (e != null) {
            if (e.taskId() == null
                    || !ids.contains(e.taskId())
                    || !Set.of("UPSTREAM", "DOWNSTREAM")
                            .contains(Objects.toString(e.direction(), "")))
                throw ApiException.invalid("Invalid expansion root or direction");
            int limit = e.limit() == null ? 20 : e.limit();
            if (limit < 1 || limit > 20)
                throw ApiException.invalid("Expansion limit must be 1..20");
            UUID after = parseCursor(e.cursor(), project, e, state.topologyRevision());
            var added = new ArrayList<UUID>();
            String next = e.cursor();
            boolean more = true;
            if (ids.size() < MAX_NODES) {
                var page = neighbors(project, List.of(e.taskId()), e.direction(), after, limit + 1);
                int consumed = 0;
                UUID last = after;
                for (UUID id : page) {
                    if (consumed == limit || (!ids.contains(id) && ids.size() == MAX_NODES)) break;
                    if (ids.add(id)) added.add(id);
                    last = id;
                    consumed++;
                }
                more = consumed < page.size();
                next =
                        more && last != null
                                ? cursor(project, e, state.topologyRevision(), last)
                                : null;
            }
            expansion = new Expansion(next, more, added);
        }
        return snapshot(project, state, ids, "view", MAX_NODES, MAX_EDGES, expansion);
    }

    private String cursor(UUID project, Expand e, String topology, UUID last) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (project
                                        + "|"
                                        + e.taskId()
                                        + "|"
                                        + e.direction()
                                        + "|"
                                        + topology
                                        + "|"
                                        + last)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private UUID parseCursor(String value, UUID project, Expand e, String topology) {
        if (value == null) return null;
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            String[] bits =
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                            .split("\\|", -1);
            if (bits.length != 5
                    || !bits[0].equals(project.toString())
                    || !bits[1].equals(e.taskId().toString())
                    || !bits[2].equals(e.direction())) throw new IllegalArgumentException();
            if (!bits[3].equals(topology))
                throw new ApiException(409, "GRAPH_CURSOR_STALE", "依赖已变化，请从这一方向的第一页继续");
            return UUID.fromString(bits[4]);
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalid("Invalid expansion cursor");
        }
    }

    Snapshot snapshot(
            UUID project,
            OverviewState.State state,
            Collection<UUID> ids,
            String mode,
            int nl,
            int el,
            Expansion expansion) {
        var edgePage = edges(project, ids, el + 1);
        boolean edgePartial = edgePage.size() > el;
        var edges = edgePartial ? List.copyOf(edgePage.subList(0, el)) : edgePage;
        var nodes = nodes(project, ids, edges);
        boolean nodePartial = nodes.size() < state.totalTasks();
        return new Snapshot(
                state.viewSeq(),
                state.businessRevision(),
                state.topologyRevision(),
                state.counts(),
                state.totalTasks(),
                state.totalEdges(),
                nodePartial || edgePartial || edges.size() < state.totalEdges(),
                new Scope(mode, nl, el, nodePartial, edgePartial),
                nodes,
                edges,
                expansion);
    }

    List<Dependency> edges(UUID project, Collection<UUID> ids, int limit) {
        if (ids.isEmpty()) return List.of();
        var a = args(project, ids);
        ids.forEach(id -> a.add(bytes(id)));
        a.add(limit);
        return db.query(
                "SELECT blocker_id,blocked_id FROM task_dependency WHERE project_id=? AND"
                        + " blocker_id IN ("
                        + marks(ids.size())
                        + ") AND blocked_id IN ("
                        + marks(ids.size())
                        + ") ORDER BY blocker_id,blocked_id LIMIT ?",
                (r, n) -> new Dependency(read(r, "blocker_id"), read(r, "blocked_id")),
                a.toArray());
    }

    List<Node> nodes(UUID project, Collection<UUID> ids, List<Dependency> edges) {
        if (ids.isEmpty()) return List.of();
        var in = new HashMap<UUID, Integer>();
        var out = new HashMap<UUID, Integer>();
        if (edges != null)
            edges.forEach(
                    e -> {
                        in.merge(e.blockedId(), 1, Integer::sum);
                        out.merge(e.blockerId(), 1, Integer::sum);
                    });
        // Each aggregate is restricted to the requested IDs. No per-node JDBC round trips.
        String m = marks(ids.size());
        var a = args(project, ids);
        a.add(bytes(project));
        ids.forEach(id -> a.add(bytes(id)));
        a.add(bytes(project));
        ids.forEach(id -> a.add(bytes(id)));
        return db.query(
                """
SELECT t.id,LEFT(t.title,160) title,CHAR_LENGTH(t.title)>160 title_truncated,t.status,t.type,t.priority,
t.assignee_id,u.display_name,(u.active AND pm.active AND wm.active) active,t.due_date,t.origin,t.triage_required,t.revision,t.discussion_revision,
COALESCE(up.cnt,0) upstream_count,COALESCE(down.cnt,0) downstream_count,COALESCE(up.blocked,0)>0 is_blocked
FROM task t LEFT JOIN app_user u ON u.id=t.assignee_id
LEFT JOIN project p ON p.id=t.project_id LEFT JOIN project_member pm ON pm.project_id=t.project_id AND pm.user_id=t.assignee_id
LEFT JOIN workspace_member wm ON wm.workspace_id=p.workspace_id AND wm.user_id=t.assignee_id
LEFT JOIN (SELECT d.blocked_id,COUNT(*) cnt,SUM(b.status<>'DONE') blocked FROM task_dependency d
  JOIN task b ON b.id=d.blocker_id AND b.project_id=d.project_id
  WHERE d.project_id=? AND d.blocked_id IN ("""
                        + m
                        + ") GROUP BY d.blocked_id) up ON up.blocked_id=t.id LEFT JOIN (SELECT"
                        + " blocker_id,COUNT(*) cnt FROM task_dependency WHERE project_id=? AND"
                        + " blocker_id IN ("
                        + m
                        + ") GROUP BY blocker_id) down ON down.blocker_id=t.id "
                        + "WHERE t.project_id=? AND t.id IN ("
                        + m
                        + ") ORDER BY t.id",
                (r, n) -> {
                    UUID id = read(r, "id"), owner = read(r, "assignee_id");
                    var status = TaskStatus.valueOf(r.getString("status"));
                    boolean blocked = r.getBoolean("is_blocked");
                    return new Node(
                            id,
                            r.getString("title"),
                            r.getBoolean("title_truncated"),
                            status,
                            TaskType.valueOf(r.getString("type")),
                            r.getInt("priority"),
                            owner,
                            owner == null
                                    ? null
                                    : new Person(
                                            owner,
                                            r.getString("display_name"),
                                            r.getBoolean("active")),
                            r.getObject("due_date", LocalDate.class),
                            TaskOrigin.valueOf(r.getString("origin")),
                            r.getBoolean("triage_required"),
                            blocked,
                            TaskSemantics.dependencyRisk(status, blocked),
                            r.getLong("revision"),
                            r.getLong("discussion_revision"),
                            r.getInt("upstream_count"),
                            r.getInt("downstream_count"),
                            edges == null ? null : in.getOrDefault(id, 0),
                            edges == null ? null : out.getOrDefault(id, 0));
                },
                a.toArray());
    }
}
