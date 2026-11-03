package dev.workhub.phase1;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrencyIT extends Phase1Support {
    @RepeatedTest(10)
    void f02OppositeEdgesWaitForTheSameProjectLock(RepetitionInfo repetition) throws Exception {
        Task a = task("A"), b = task("B");
        long revision = revision();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstApplied = new CountDownLatch(1),
                secondStarted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        var connectionIds = new ConcurrentHashMap<String, Long>();
        long started = System.nanoTime();
        try {
            Future<String> first =
                    executor.submit(
                            () ->
                                    transaction()
                                            .execute(
                                                    tx -> {
                                                        connectionIds.put(
                                                                "T1",
                                                                jdbc.queryForObject(
                                                                        "SELECT CONNECTION_ID()",
                                                                        Long.class));
                                                        dag.add(
                                                                actor,
                                                                project,
                                                                revision,
                                                                new Dependency(a.id(), b.id()),
                                                                UUID.randomUUID().toString());
                                                        firstApplied.countDown();
                                                        await(release);
                                                        return "COMMITTED";
                                                    }));
            assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second =
                    executor.submit(
                            () -> {
                                try {
                                    return transaction()
                                            .execute(
                                                    tx -> {
                                                        connectionIds.put(
                                                                "T2",
                                                                jdbc.queryForObject(
                                                                        "SELECT CONNECTION_ID()",
                                                                        Long.class));
                                                        secondStarted.countDown();
                                                        dag.add(
                                                                actor,
                                                                project,
                                                                revision,
                                                                new Dependency(b.id(), a.id()),
                                                                UUID.randomUUID().toString());
                                                        return "COMMITTED";
                                                    });
                                } catch (ApiException e) {
                                    return e.code;
                                }
                            });
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            String locks = lockEvidence(connectionIds.get("T2"));
            assertThat(locks).contains("project").contains("PRIMARY");
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("COMMITTED");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("PROJECT_REVISION_CONFLICT");
            assertThat(edges.exists(project, new Dependency(a.id(), b.id()))).isTrue();
            assertThat(edges.exists(project, new Dependency(b.id(), a.id()))).isFalse();
            assertThat(revision()).isEqualTo(revision + 1);
            assertThatThrownBy(
                            () ->
                                    dag.add(
                                            actor,
                                            project,
                                            revision(),
                                            new Dependency(b.id(), a.id()),
                                            UUID.randomUUID().toString()))
                    .satisfies(e -> expectCode(e, "DEPENDENCY_CYCLE"));
            save(
                    "F02-" + repetition.getCurrentRepetition(),
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "project",
                            project.toString(),
                            "connectionIds",
                            connectionIds,
                            "outcomes",
                            List.of(
                                    "COMMITTED",
                                    "PROJECT_REVISION_CONFLICT",
                                    "DEPENDENCY_CYCLE_ON_REFRESH"),
                            "lockWait",
                            locks,
                            "elapsedMicros",
                            (System.nanoTime() - started) / 1000,
                            "finalRevision",
                            revision(),
                            "finalEdges",
                            List.of(a.id() + " -> " + b.id())));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 20})
    void f03SameTaskRevisionHasOneWinnerAndNoSilentOverwrite(int writers) throws Exception {
        for (int trial = 1; trial <= 10; trial++) {
            Task task = task("original");
            long baseProject = revision();
            var start = new CyclicBarrier(writers);
            var executor = Executors.newFixedThreadPool(writers);
            List<Future<String>> futures = new ArrayList<>();
            Set<Long> connections = ConcurrentHashMap.newKeySet();
            try {
                for (int i = 0; i < writers; i++) {
                    String title = "writer-" + i;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        start.await(5, TimeUnit.SECONDS);
                                        try {
                                            return transaction()
                                                    .execute(
                                                            tx -> {
                                                                connections.add(
                                                                        jdbc.queryForObject(
                                                                                "SELECT"
                                                                                    + " CONNECTION_ID()",
                                                                                Long.class));
                                                                var patch =
                                                                        new TaskRequests.Patch();
                                                                patch.setTitle(title);
                                                                tasks.update(
                                                                        actor,
                                                                        project,
                                                                        task.id(),
                                                                        task.revision(),
                                                                        patch);
                                                                return title;
                                                            });
                                        } catch (ApiException e) {
                                            return e.code;
                                        }
                                    }));
                }
                List<String> results = new ArrayList<>();
                for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
                List<String> winners =
                        results.stream().filter(v -> v.startsWith("writer-")).toList();
                assertThat(winners).hasSize(1);
                assertThat(results.stream().filter("TASK_REVISION_CONFLICT"::equals).count())
                        .isEqualTo(writers - 1);
                Task finalTask = tasks.get(actor, project, task.id());
                assertThat(finalTask.title()).isEqualTo(winners.get(0));
                assertThat(finalTask.revision()).isEqualTo(1);
                assertThat(revision()).isEqualTo(baseProject + 1);
                assertThat(connections.size()).isGreaterThanOrEqualTo(2);
                save(
                        "F03-" + writers + "-" + trial,
                        Map.of(
                                "writers",
                                writers,
                                "trial",
                                trial,
                                "connections",
                                connections,
                                "outcomes",
                                results,
                                "finalTitle",
                                finalTask.title(),
                                "finalTaskRevision",
                                finalTask.revision(),
                                "finalProjectRevision",
                                revision()));
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Checkpoint timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Read-only diagnostics using the existing local Compose root identity; no new DB grants.
    private static String lockEvidence(long connection) throws Exception {
        String sql =
                "SELECT t.PROCESSLIST_ID AS waiting_connection,b.PROCESSLIST_ID AS"
                    + " blocking_connection,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_TYPE,l.LOCK_MODE,l.LOCK_STATUS"
                    + " FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t"
                    + " ON t.THREAD_ID=w.REQUESTING_THREAD_ID JOIN performance_schema.threads b ON"
                    + " b.THREAD_ID=w.BLOCKING_THREAD_ID JOIN performance_schema.data_locks l ON"
                    + " l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE t.PROCESSLIST_ID="
                        + connection;
        var process =
                new ProcessBuilder(
                                "docker",
                                "compose",
                                "exec",
                                "-T",
                                "mysql",
                                "sh",
                                "-c",
                                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --batch -e '"
                                        + sql
                                        + "'")
                        .redirectErrorStream(true)
                        .start();
        assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
        String result =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(result).isZero();
        return result;
    }

    static void save(String name, Object value) throws Exception {
        Path directory = EVIDENCE.resolve("concurrency");
        Files.createDirectories(directory);
        String result =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(value);
        Files.writeString(directory.resolve(name + ".json"), result + "\n");
        System.out.println("PHASE1_EVIDENCE " + name + " " + result);
    }
}
