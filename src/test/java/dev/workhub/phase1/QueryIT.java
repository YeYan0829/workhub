package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.work.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.*;
import java.util.*;

class QueryIT extends Phase1Support {
    @Test
    void utcDatetimeAndKeysetPaginationDoNotSkipTimestampTies() {
        List<Task> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) created.add(task("page " + i));
        Instant fixed = Instant.parse("2026-09-16T00:00:00.123456Z");
        jdbc.update(
                "UPDATE task SET updated_at=? WHERE project_id=?",
                dev.workhub.common.Utc.value(fixed),
                bytes(project));
        List<UUID> seen = new ArrayList<>();
        TaskRepository.Cursor cursor = null;
        while (true) {
            var page = tasks.list(actor, project, null, null, null, cursor, 2);
            if (page.isEmpty()) break;
            for (Task task : page) {
                seen.add(task.id());
                assertThat(task.updatedAt()).isEqualTo(fixed);
            }
            Task last = page.get(page.size() - 1);
            cursor = new TaskRepository.Cursor(last.updatedAt(), last.id());
        }
        assertThat(seen)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(created.stream().map(Task::id).toList());
        assertThat(tasks.get(actor, project, created.get(0).id()).createdAt())
                .isCloseTo(Instant.now(), within(30, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void recursivePlanUsesProjectAndBlockerIndexAndIgnoresUnrelatedEdges() throws Exception {
        Task a = task("A"), b = task("B"), c = task("unrelated C"), d = task("unrelated D");
        add(a, b);
        add(c, d);
        String sql =
                """
EXPLAIN ANALYZE WITH RECURSIVE reach(id) AS (
  SELECT CAST(? AS BINARY(16)) UNION DISTINCT
  SELECT d.blocked_id FROM task_dependency d JOIN reach r ON d.blocker_id=r.id WHERE d.project_id=?
) SELECT MAX(id=?),COUNT(*) FROM reach
""";
        String plan =
                jdbc.queryForObject(
                        sql, String.class, bytes(a.id()), bytes(project), bytes(b.id()));
        // MySQL can choose either project-prefixed covering index for a tiny fixture.
        // Assert indexed/project-scoped access plus the required physical key, not one cost
        // decision.
        assertThat(plan.toLowerCase(Locale.ROOT))
                .contains("index lookup")
                .contains("project_id")
                .contains("blocker_id");
        assertThat(
                        jdbc.queryForList(
                                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME='task_dependency'"
                                    + " AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",
                                String.class))
                .containsExactly("project_id", "blocker_id", "blocked_id");
        var reach =
                transaction()
                        .execute(
                                tx ->
                                        edges.reachable(
                                                project, a.id(), b.id(), new DependencyBudget(2)));
        assertThat(reach.visitedNodes()).isEqualTo(2);
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("cte-explain.txt"), plan + "\n");
    }
}
