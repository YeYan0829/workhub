package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.util.*;

class RollbackIT extends Phase1Support {
    @Autowired ApplicationContext context;

    @Test
    void realRecursionLimitRollsBackPriorTaskSourceOutboxAndEdgeDeletion() {
        List<UUID> chain = new ArrayList<>();
        for (int i = 0; i < 1005; i++) chain.add(UUID.randomUUID());
        jdbc.batchUpdate(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,'deep','','TODO','TASK','MANUAL',?)",
                chain,
                100,
                (p, id) -> {
                    p.setBytes(1, bytes(id));
                    p.setBytes(2, bytes(project));
                    p.setBytes(3, bytes(actor.userId()));
                });
        List<Dependency> links = new ArrayList<>();
        for (int i = 0; i < chain.size() - 1; i++)
            links.add(new Dependency(chain.get(i), chain.get(i + 1)));
        jdbc.batchUpdate(
                "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES"
                        + " (?,?,?,?)",
                links,
                100,
                (p, e) -> {
                    p.setBytes(1, bytes(project));
                    p.setBytes(2, bytes(e.blockerId()));
                    p.setBytes(3, bytes(e.blockedId()));
                    p.setBytes(4, bytes(actor.userId()));
                });
        Task a = task("unrelated A"), b = task("unrelated B");
        add(a, b);
        var before = counts();
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                tx -> {
                                                    task(
                                                            "must rollback",
                                                            TaskStatus.TODO,
                                                            other.userId());
                                                    dag.replace(
                                                            actor,
                                                            project,
                                                            revision(),
                                                            List.of(new Dependency(a.id(), b.id())),
                                                            List.of(
                                                                    new Dependency(
                                                                            chain.get(
                                                                                    chain.size()
                                                                                            - 1),
                                                                            chain.get(0))));
                                                    return null;
                                                }))
                .satisfies(e -> expectCode(e, "DEPENDENCY_CHECK_LIMIT"));
        assertThat(counts()).isEqualTo(before);
        assertThat(edges.exists(project, new Dependency(a.id(), b.id()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT @@session.cte_max_recursion_depth", Integer.class))
                .isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void realStatementTimeoutPropagatesThroughServiceAndRollsBackEverything() {
        Task a = task("A"), b = task("B"), c = task("C");
        add(a, b);
        var before = counts();
        // Test-only SQL fault injector. Uses a real MySQL recursive statement and driver
        // cancellation.
        // The production service still owns all validation, mutation and error propagation.
        DependencyRepository slow =
                new DependencyRepository(jdbc, 1000) {
                    @Override
                    public Reachability reachable(
                            UUID p, UUID start, UUID target, DependencyBudget budget) {
                        jdbc.execute(
                                (ConnectionCallback<Void>)
                                        connection -> {
                                            try (var statement = connection.createStatement()) {
                                                statement.setQueryTimeout(1);
                                                statement.executeQuery(
                                                        "WITH RECURSIVE slow(n) AS (SELECT SLEEP(5)"
                                                            + " UNION DISTINCT SELECT n+1 FROM slow"
                                                            + " WHERE n<1) SELECT n FROM slow");
                                            }
                                            return null;
                                        });
                        return super.reachable(p, start, target, budget);
                    }
                };
        DependencyService service = withRepository(slow);
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                tx -> {
                                                    task(
                                                            "must rollback",
                                                            TaskStatus.TODO,
                                                            other.userId());
                                                    service.replace(
                                                            actor,
                                                            project,
                                                            revision(),
                                                            List.of(new Dependency(a.id(), b.id())),
                                                            List.of(
                                                                    new Dependency(
                                                                            b.id(), c.id())));
                                                    return null;
                                                }))
                .satisfies(e -> expectCode(e, "DEPENDENCY_CHECK_LIMIT"));
        assertThat(counts()).isEqualTo(before);
        assertThat(edges.exists(project, new Dependency(a.id(), b.id()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void realSqlErrorAndBusinessFailureAfterWritesRollbackAllLocalEffects() {
        Task a = task("A"), b = task("B"), c = task("C");
        add(a, b);
        var before = counts();
        DependencyRepository broken =
                new DependencyRepository(jdbc, 1000) {
                    @Override
                    public Reachability reachable(
                            UUID p, UUID start, UUID target, DependencyBudget budget) {
                        jdbc.queryForObject(
                                "SELECT nonexistent_phase1_column FROM task WHERE project_id=?"
                                        + " LIMIT 1",
                                Integer.class,
                                bytes(p));
                        return super.reachable(p, start, target, budget);
                    }
                };
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                tx -> {
                                                    task(
                                                            "must rollback",
                                                            TaskStatus.TODO,
                                                            other.userId());
                                                    withRepository(broken)
                                                            .replace(
                                                                    actor,
                                                                    project,
                                                                    revision(),
                                                                    List.of(
                                                                            new Dependency(
                                                                                    a.id(),
                                                                                    b.id())),
                                                                    List.of(
                                                                            new Dependency(
                                                                                    b.id(),
                                                                                    c.id())));
                                                    return null;
                                                }))
                .isInstanceOf(DataAccessException.class);
        assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                tx -> {
                                                    task(
                                                            "also rollback",
                                                            TaskStatus.TODO,
                                                            other.userId());
                                                    var patch = new TaskRequests.Patch();
                                                    patch.setAssigneeId(UUID.randomUUID());
                                                    tasks.update(
                                                            actor,
                                                            project,
                                                            a.id(),
                                                            a.revision(),
                                                            patch);
                                                    return null;
                                                }))
                .satisfies(e -> expectCode(e, "VALIDATION_ERROR"));
        assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                tx -> {
                                                    task(
                                                            "stale rollback",
                                                            TaskStatus.TODO,
                                                            other.userId());
                                                    var patch = new TaskRequests.Patch();
                                                    patch.setTitle("stale");
                                                    tasks.update(actor, project, a.id(), 99, patch);
                                                    return null;
                                                }))
                .satisfies(e -> expectCode(e, "TASK_REVISION_CONFLICT"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void projectLockWaitExhaustsTheProductionTransactionBudget() throws Exception {
        Task a = task("A"), b = task("B");
        var before = counts();
        long expected = revision();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement =
                    holder.prepareStatement("SELECT id FROM project WHERE id=? FOR UPDATE")) {
                statement.setBytes(1, bytes(project));
                statement.executeQuery().close();
            }
            long start = System.nanoTime();
            var result =
                    executor.submit(
                            () -> {
                                try {
                                    dag.add(
                                            actor,
                                            project,
                                            expected,
                                            new Dependency(a.id(), b.id()),
                                            UUID.randomUUID().toString());
                                    return "unexpected commit";
                                } catch (ApiException error) {
                                    return error.code;
                                }
                            });
            assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo("DEPENDENCY_CHECK_LIMIT");
            assertThat((System.nanoTime() - start) / 1_000_000).isBetween(900L, 4500L);
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(counts()).isEqualTo(before);
    }

    private DependencyService withRepository(DependencyRepository repository) {
        return new DependencyService(
                context.getBean(ProjectAccess.class),
                context.getBean(ProjectRepository.class),
                taskRepository,
                repository,
                context.getBean(ActivityService.class),
                context.getBean(OperationReceipts.class),
                context.getBean(Json.class),
                2);
    }
}
