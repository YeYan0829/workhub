package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class DependencyRepository {
    private static final Logger log = LoggerFactory.getLogger(DependencyRepository.class);
    private final JdbcTemplate jdbc;
    private final int maxDepth;

    public DependencyRepository(
            JdbcTemplate jdbc,
            @Value("${workhub.dependency.max-recursion-depth:1000}") int maxDepth) {
        if (maxDepth < 1) throw new IllegalArgumentException("Positive recursion limit required");
        this.jdbc = jdbc;
        this.maxDepth = maxDepth;
    }

    public boolean exists(UUID project, Dependency edge) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM task_dependency WHERE project_id=? AND blocker_id=?"
                        + " AND blocked_id=?)",
                Boolean.class,
                bytes(project),
                bytes(edge.blockerId()),
                bytes(edge.blockedId()));
    }

    public void insert(UUID project, Dependency edge, UUID creator) {
        jdbc.update(
                "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES"
                        + " (?,?,?,?)",
                bytes(project),
                bytes(edge.blockerId()),
                bytes(edge.blockedId()),
                bytes(creator));
    }

    public int delete(UUID project, Dependency edge) {
        return jdbc.update(
                "DELETE FROM task_dependency WHERE project_id=? AND blocker_id=? AND blocked_id=?",
                bytes(project),
                bytes(edge.blockerId()),
                bytes(edge.blockedId()));
    }

    public record Reachability(boolean reachable, long visitedNodes) {}

    public Reachability reachable(UUID project, UUID start, UUID target, DependencyBudget budget) {
        int millis = budget.remainingMillis();
        long began = System.nanoTime();
        // Only node IDs participate in UNION DISTINCT. No recursive LIMIT, depth or path column.
        // SET_VAR is scoped to this statement; pooled sessions are never modified.
        String sql =
                """
                WITH RECURSIVE reach(id) AS (
                  SELECT CAST(? AS BINARY(16))
                  UNION DISTINCT
                  SELECT d.blocked_id FROM task_dependency d JOIN reach r ON d.blocker_id=r.id
                  WHERE d.project_id=?
                )
                SELECT /*+ MAX_EXECUTION_TIME(%d) SET_VAR(cte_max_recursion_depth=%d) */
                  COALESCE(MAX(id=?),0) AS reachable,COUNT(*) AS visited_nodes FROM reach
                """
                        .formatted(millis, maxDepth);
        Reachability result =
                jdbc.queryForObject(
                        sql,
                        (r, n) -> new Reachability(r.getBoolean(1), r.getLong(2)),
                        bytes(start),
                        bytes(project),
                        bytes(target));
        budget.check();
        log.info(
                "DAG_CTE project={} start={} target={} reachable={} visitedNodes={} cteMicros={}",
                project,
                start,
                target,
                result.reachable(),
                result.visitedNodes(),
                (System.nanoTime() - began) / 1000);
        return result;
    }

    public List<Dependency> outgoing(UUID project, List<UUID> blockers, DependencyBudget budget) {
        String placeholders = String.join(",", Collections.nCopies(blockers.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(bytes(project));
        for (UUID id : blockers) args.add(bytes(id));
        return jdbc.query(
                "SELECT /*+ MAX_EXECUTION_TIME("
                        + budget.remainingMillis()
                        + ") */ blocker_id,blocked_id FROM task_dependency WHERE project_id=? AND"
                        + " blocker_id IN ("
                        + placeholders
                        + ") LIMIT 10001",
                (r, n) -> new Dependency(read(r, "blocker_id"), read(r, "blocked_id")),
                args.toArray());
    }
}
