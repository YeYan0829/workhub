package dev.workhub.work;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.*;

import java.sql.SQLException;
import java.util.*;

@Service
public class DependencyService {
    private static final Logger log = LoggerFactory.getLogger(DependencyService.class);
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final DependencyRepository dependencies;
    private final ActivityService activity;
    private final OperationReceipts receipts;
    private final Json json;
    private final int timeoutSeconds;

    public DependencyService(
            ProjectAccess access,
            ProjectRepository projects,
            TaskRepository tasks,
            DependencyRepository dependencies,
            ActivityService activity,
            OperationReceipts receipts,
            Json json,
            @Value("${workhub.dependency.timeout-seconds:2}") int timeoutSeconds) {
        this.access = access;
        this.projects = projects;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.activity = activity;
        this.receipts = receipts;
        this.json = json;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record Result(long businessRevision) {}

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeoutString = "${workhub.dependency.timeout-seconds:2}")
    public Result add(
            ActorContext actor, UUID project, long expected, Dependency edge, String key) {
        return execute(actor, project, expected, List.of(), List.of(edge), key);
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeoutString = "${workhub.dependency.timeout-seconds:2}")
    public Result remove(ActorContext actor, UUID project, long expected, Dependency edge) {
        return execute(actor, project, expected, List.of(edge), List.of(), null);
    }

    /** Internal local graph operation, intentionally no batch/proposal HTTP endpoint in Phase 1. */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeoutString = "${workhub.dependency.timeout-seconds:2}")
    public Result replace(
            ActorContext actor,
            UUID project,
            long expected,
            List<Dependency> removals,
            List<Dependency> additions) {
        return execute(actor, project, expected, removals, additions, null);
    }

    private Result execute(
            ActorContext actor,
            UUID project,
            long expected,
            List<Dependency> removals,
            List<Dependency> additions,
            String key) {
        var budget = new DependencyBudget(timeoutSeconds);
        long acquired = 0;
        try {
            validateBatch(removals, additions);
            Project current = access.lock(actor, project);
            acquired = System.nanoTime();
            budget.check();
            log.info("DAG_LOCK project={} lockWaitMicros={}", project, budget.elapsedMicros());
            for (Dependency edge : concat(removals, additions)) {
                validateEndpoints(project, edge);
                access.editTask(actor, tasks.find(project, edge.blockedId()).orElseThrow());
            }
            String hash = json.write(List.of("dependency", project, expected, additions));
            if (key != null && receipts.find(actor.userId(), key, Inputs.hash(hash)) != null)
                return new Result(current.businessRevision());
            access.expected(current, expected);
            LinkedHashMap<UUID, Task> affected = new LinkedHashMap<>();
            for (Dependency edge : concat(removals, additions)) {
                validateEndpoints(project, edge);
                affected.putIfAbsent(
                        edge.blockedId(), tasks.find(project, edge.blockedId()).orElseThrow());
            }
            for (Dependency edge : removals) {
                if (dependencies.delete(project, edge) != 1) throw ApiException.missing();
            }
            for (Dependency edge : additions) {
                if (dependencies.exists(project, edge))
                    throw new ApiException(409, "DEPENDENCY_EXISTS", "Dependency already exists");
                boolean reachable =
                        dependencies
                                .reachable(project, edge.blockedId(), edge.blockerId(), budget)
                                .reachable();
                if (reachable)
                    throw new ApiException(
                            422,
                            "DEPENDENCY_CYCLE",
                            "Dependency would create a cycle",
                            null,
                            witness(project, edge, budget));
                dependencies.insert(project, edge, actor.userId());
            }
            UUID cause = UUID.randomUUID();
            for (Dependency edge : removals)
                activity.record(
                        project,
                        edge.blockedId(),
                        actor,
                        UUID.randomUUID(),
                        "DEPENDENCY_REMOVED",
                        edge,
                        null);
            for (Dependency edge : additions)
                activity.record(
                        project,
                        edge.blockedId(),
                        actor,
                        UUID.randomUUID(),
                        "DEPENDENCY_ADDED",
                        null,
                        edge);
            activity.unblocked(new ArrayList<>(affected.values()), actor, cause);
            projects.increment(project);
            if (key != null) receipts.save(actor.userId(), key, project, Inputs.hash(hash), cause);
            budget.check();
            return new Result(current.businessRevision() + 1);
        } catch (DataAccessException error) {
            if (isLimit(error)) throw ApiException.limit();
            throw error; // Never commit earlier removals after an SQL failure.
        } catch (TransactionTimedOutException error) {
            throw ApiException.limit();
        } finally {
            log.info(
                    "DAG_TX project={} totalMicros={} heldMicros={}",
                    project,
                    budget.elapsedMicros(),
                    acquired == 0 ? 0 : (System.nanoTime() - acquired) / 1000);
        }
    }

    private void validateEndpoints(UUID project, Dependency edge) {
        if (edge.blockerId() == null || edge.blockedId() == null)
            throw ApiException.invalid("Both task IDs are required");
        if (edge.blockerId().equals(edge.blockedId()))
            throw new ApiException(422, "DEPENDENCY_SELF_LOOP", "A task cannot block itself");
        if (tasks.find(project, edge.blockerId()).isEmpty()
                || tasks.find(project, edge.blockedId()).isEmpty())
            throw new ApiException(
                    422, "PROJECT_SCOPE_ERROR", "Dependency endpoints must exist in this project");
    }

    private static List<Dependency> concat(List<Dependency> a, List<Dependency> b) {
        List<Dependency> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void validateBatch(List<Dependency> removals, List<Dependency> additions) {
        if (removals.size() + additions.size() == 0 || removals.size() + additions.size() > 20)
            throw ApiException.invalid("Expected 1 to 20 graph operations");
        var all = concat(removals, additions);
        if (new HashSet<>(all).size() != all.size())
            throw ApiException.invalid("Duplicate or contradictory dependency operations");
    }

    // Error diagnostics only. Bounded breadth-first traversal records one parent per node.
    private List<UUID> witness(UUID project, Dependency proposed, DependencyBudget budget) {
        Map<UUID, UUID> parent = new HashMap<>();
        parent.put(proposed.blockedId(), null);
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(proposed.blockedId());
        while (!queue.isEmpty()) {
            budget.check();
            List<UUID> batch = new ArrayList<>();
            while (!queue.isEmpty() && batch.size() < 128) batch.add(queue.remove());
            var edges = dependencies.outgoing(project, batch, budget);
            if (edges.size() > 10000) throw ApiException.limit();
            for (Dependency edge : edges) {
                if (parent.containsKey(edge.blockedId())) continue;
                parent.put(edge.blockedId(), edge.blockerId());
                if (edge.blockedId().equals(proposed.blockerId())) {
                    List<UUID> path = new ArrayList<>();
                    UUID cursor = proposed.blockerId();
                    while (cursor != null) {
                        path.add(cursor);
                        cursor = parent.get(cursor);
                    }
                    Collections.reverse(path);
                    path.add(0, proposed.blockerId());
                    budget.check();
                    return path;
                }
                queue.add(edge.blockedId());
                if (parent.size() > 10000) throw ApiException.limit();
            }
        }
        throw new IllegalStateException("Reachability and diagnostic path disagree");
    }

    private static boolean isLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTimeoutException) return true;
            if (cause instanceof SQLException sql
                    && Set.of(3636, 3024, 1317, 1205).contains(sql.getErrorCode())) return true;
        }
        return false;
    }
}
