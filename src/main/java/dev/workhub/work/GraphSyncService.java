package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class GraphSyncService {
    private final JdbcTemplate db;
    private final ProjectAccess access;
    private final OverviewState states;
    private final GraphReadService graph;

    public GraphSyncService(
            JdbcTemplate db, ProjectAccess access, OverviewState states, GraphReadService graph) {
        this.db = db;
        this.access = access;
        this.states = states;
        this.graph = graph;
    }

    public record Request(String afterSeq, List<UUID> nodeIds, UUID selectedTaskId) {}

    public record Delta(
            String fromSeq,
            String toSeq,
            long businessRevision,
            String topologyRevision,
            List<GraphReadService.Node> nodesUpsert,
            List<UUID> nodesRemove,
            List<Dependency> edgesReplace,
            Map<String, Long> counts,
            long totalTasks,
            long totalEdges,
            List<UUID> previewDirtyTaskIds,
            boolean documentsDirty,
            boolean discoveryChanged,
            boolean projectDirty,
            boolean edgePartial) {}

    private record Change(
            String kind, UUID task, UUID blocker, UUID blocked, boolean status, boolean scope) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Delta sync(ActorContext actor, UUID project, Request request) {
        access.read(actor, project);
        if (request == null) throw ApiException.invalid("Missing sync request");
        long after = OverviewState.sequence(request.afterSeq());
        var ids = GraphReadService.validate(request.nodeIds());
        // Existing foreign-project IDs are rejected; deleted IDs can be returned in nodesRemove.
        if (!ids.isEmpty()) {
            var a = new ArrayList<Object>();
            ids.forEach(id -> a.add(bytes(id)));
            a.add(bytes(project));
            if (db.queryForObject(
                            "SELECT COUNT(*) FROM task WHERE id IN ("
                                    + GraphReadService.marks(ids.size())
                                    + ") AND project_id<>?",
                            Long.class,
                            a.toArray())
                    > 0) throw ApiException.invalid("Every node must belong to this project");
        }
        if (request.selectedTaskId() != null)
            graph.requireIds(project, List.of(request.selectedTaskId()));
        var state = states.read(project);
        long to = OverviewState.sequence(state.viewSeq());
        if (after < OverviewState.sequence(state.retainedAfterSeq()) || after > to) throw resync();
        var changes =
                db.query(
                        "SELECT kind,task_id,blocker_id,blocked_id,status_changed,scope_dirty FROM"
                            + " project_view_change WHERE project_id=? AND seq>? AND seq<=? ORDER"
                            + " BY seq LIMIT 1001",
                        (r, n) ->
                                new Change(
                                        r.getString("kind"),
                                        read(r, "task_id"),
                                        read(r, "blocker_id"),
                                        read(r, "blocked_id"),
                                        r.getBoolean("status_changed"),
                                        r.getBoolean("scope_dirty")),
                        bytes(project),
                        after,
                        to);
        if (changes.size() > 1000) throw resync();
        var affected = new LinkedHashSet<UUID>();
        var statusChanged = new LinkedHashSet<UUID>();
        var dirty = new LinkedHashSet<UUID>();
        var removed = new LinkedHashSet<UUID>();
        boolean topology = false, documents = false, discovery = false, projectDirty = false;
        for (var c : changes) {
            if (c.task() != null
                    && (ids.contains(c.task()) || c.task().equals(request.selectedTaskId())))
                dirty.add(c.task());
            switch (c.kind()) {
                case "DISCUSSION_CHANGED" -> {
                    /* Graph entity data remains empty. */
                }
                case "TASK_CREATED" -> {
                    discovery = true;
                    if (ids.contains(c.task())) affected.add(c.task());
                }
                case "TASK_REMOVED" -> {
                    topology = true;
                    discovery = true;
                    if (ids.contains(c.task())) removed.add(c.task());
                }
                case "TASK_CHANGED" -> {
                    if (ids.contains(c.task())) affected.add(c.task());
                    if (c.status()) statusChanged.add(c.task());
                }
                case "DEPENDENCY_CHANGED" -> {
                    topology = true;
                    affected.addAll(ids);
                }
                case "RESOURCE_CHANGED" -> {
                    documents = true;
                    if (c.scope()) dirty.addAll(ids);
                }
                case "PROJECT_CHANGED" -> projectDirty = true;
                case "ACCESS_CHANGED" -> {
                    projectDirty = true;
                    affected.addAll(ids);
                }
                default -> throw resync();
            }
        }
        if (!statusChanged.isEmpty() && !ids.isEmpty()) {
            var a = GraphReadService.args(project, statusChanged);
            ids.forEach(id -> a.add(bytes(id)));
            affected.addAll(
                    db.query(
                            "SELECT DISTINCT blocked_id FROM task_dependency WHERE project_id=? AND"
                                    + " blocker_id IN ("
                                    + GraphReadService.marks(statusChanged.size())
                                    + ") AND blocked_id IN ("
                                    + GraphReadService.marks(ids.size())
                                    + ")",
                            (r, n) -> read(r, "blocked_id"),
                            a.toArray()));
        }
        affected.removeAll(removed);
        dirty.addAll(affected);
        ids.removeAll(removed);
        List<Dependency> edgePage =
                topology
                        ? graph.edges(project, ids, GraphReadService.MAX_EDGES + 1)
                        : List.of();
        boolean edgePartial = edgePage.size() > GraphReadService.MAX_EDGES;
        var edges =
                edgePartial
                        ? List.copyOf(edgePage.subList(0, GraphReadService.MAX_EDGES))
                        : edgePage;
        // Field changes preserve the existing edge set. Its visibility is owned by
        // the current canvas; only topology changes need to read and replace edges.
        var nodes = graph.nodes(project, affected, topology ? edges : null);
        return new Delta(
                request.afterSeq(),
                state.viewSeq(),
                state.businessRevision(),
                state.topologyRevision(),
                nodes,
                List.copyOf(removed),
                topology ? edges : null,
                state.counts(),
                state.totalTasks(),
                state.totalEdges(),
                List.copyOf(dirty),
                documents,
                discovery,
                projectDirty,
                edgePartial);
    }

    private ApiException resync() {
        return new ApiException(409, "GRAPH_RESYNC_REQUIRED", "同步记录已过期，请重新读取当前范围");
    }
}
