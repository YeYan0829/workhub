package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.work.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

class DagOracleIT extends Phase1Support {
    static Stream<Arguments> graphs() {
        return Stream.of(
                Arguments.of("empty", new int[][] {}),
                Arguments.of("chain", new int[][] {{0, 1}, {1, 2}}),
                Arguments.of("diamond", new int[][] {{0, 1}, {0, 2}, {1, 3}, {2, 3}}),
                Arguments.of("unrelated", new int[][] {{0, 1}, {2, 3}, {3, 4}}),
                Arguments.of("longer", new int[][] {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}}),
                Arguments.of(
                        "fanout",
                        new int[][] {
                            {0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5}, {1, 5}, {2, 5}, {3, 5}
                        }));
    }

    @ParameterizedTest
    @MethodSource("graphs")
    void recursiveCteAgreesWithIndependentWholeGraphOracle(String name, int[][] graph) {
        List<Task> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) nodes.add(task(name + i));
        for (int[] edge : graph)
            jdbc.update(
                    "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by)"
                            + " VALUES (?,?,?,?)",
                    bytes(project),
                    bytes(nodes.get(edge[0]).id()),
                    bytes(nodes.get(edge[1]).id()),
                    bytes(actor.userId()));
        for (int from = 0; from < 6; from++)
            for (int to = 0; to < 6; to++) {
                boolean expected = reachable(graph, from, to);
                int start = from, target = to;
                var actual =
                        transaction()
                                .execute(
                                        status ->
                                                edges.reachable(
                                                        project,
                                                        nodes.get(start).id(),
                                                        nodes.get(target).id(),
                                                        new DependencyBudget(2)));
                assertThat(actual.reachable())
                        .as("%s %s -> %s", name, from, to)
                        .isEqualTo(expected);
            }
    }

    // Test-only whole-graph oracle. Production never loads the whole graph for validation.
    static boolean reachable(int[][] edges, int start, int target) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int node = queue.remove();
            if (node == target) return true;
            if (seen.add(node)) for (int[] edge : edges) if (edge[0] == node) queue.add(edge[1]);
        }
        return false;
    }
}
