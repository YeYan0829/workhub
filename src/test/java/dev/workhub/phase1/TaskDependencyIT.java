package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.*;
import dev.workhub.identity.ProjectRole;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.Stream;

class TaskDependencyIT extends Phase1Support {
    static Stream<Arguments> states() {
        return Arrays.stream(TaskStatus.values())
                .flatMap(
                        up ->
                                Arrays.stream(TaskStatus.values())
                                        .map(down -> Arguments.of(up, down)));
    }

    @ParameterizedTest
    @MethodSource("states")
    void f01DirectDependencyIsIndependentOfDownstreamStatus(
            TaskStatus upstream, TaskStatus downstream) {
        Task a = task("A", upstream, null), b = task("B", downstream, other.userId());
        add(a, b);
        Task result = tasks.get(actor, project, b.id());
        assertThat(result.isBlocked()).isEqualTo(upstream != TaskStatus.DONE);
        assertThat(result.status()).isEqualTo(downstream);
        assertThat(result.revision()).isZero();
    }

    @Test
    void f01MultipleBlockersReopenAndDeletionHaveExactFactsAndNotifications() {
        Task a = task("A"),
                b = task("B", TaskStatus.DONE, other.userId()),
                c = task("C"),
                d = task("D");
        add(a, b);
        add(c, b);
        add(b, d);
        assertThat(tasks.get(actor, project, d.id()).isBlocked())
                .isFalse(); // Direct, not transitive.
        long initialNotices = count("notification"), bRevision = b.revision();
        state(a, TaskStatus.DONE);
        assertThat(count("notification")).isEqualTo(initialNotices);
        state(c, TaskStatus.CANCELED);
        assertThat(tasks.get(actor, project, b.id()).isBlocked()).isTrue();
        state(c, TaskStatus.DONE);
        assertThat(count("notification")).isEqualTo(initialNotices + 1);
        state(c, TaskStatus.DONE);
        assertThat(count("notification")).isEqualTo(initialNotices + 1);
        long beforeReopen = revision();
        state(a, TaskStatus.TODO);
        assertThat(revision()).isEqualTo(beforeReopen + 1);
        Task stillDone = tasks.get(actor, project, b.id());
        assertThat(stillDone.status()).isEqualTo(TaskStatus.DONE);
        assertThat(stillDone.revision()).isEqualTo(bRevision);
        assertThat(stillDone.isBlocked()).isTrue();
        dag.remove(actor, project, revision(), new Dependency(a.id(), b.id()));
        assertThat(tasks.get(actor, project, b.id()).isBlocked()).isFalse();
        assertThat(count("notification")).isEqualTo(initialNotices + 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM activity WHERE project_id=? AND"
                                        + " kind='TASK_REOPENED'",
                                Long.class,
                                bytes(project)))
                .isEqualTo(1);
    }

    @Test
    void upstreamActorReceivesOwnDownstreamUnblockedNoticeOnceButNotOwnOrdinaryNotice() {
        Task a = task("Own A", TaskStatus.TODO, actor.userId());
        Task b = task("Own B", TaskStatus.TODO, actor.userId());
        Task c = task("Other blocker");
        add(a, b);
        add(c, b);
        assertThat(count("notification")).isZero(); // Own assignments remain silent.
        state(a, TaskStatus.DONE);
        assertThat(count("notification")).isZero(); // Another blocker still remains.
        state(c, TaskStatus.CANCELED);
        assertThat(count("notification")).isZero(); // Cancellation does not satisfy a dependency.
        state(c, TaskStatus.DONE);
        var notices = activity.notifications(actor, project, Long.MAX_VALUE, 100);
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).taskId()).isEqualTo(b.id());
        assertThat(notices.get(0).kind()).isEqualTo("TASK_UNBLOCKED");
        assertThat(notices.get(0).reasonSet()).contains("UNBLOCKED");
        state(c, TaskStatus.DONE);
        assertThat(count("notification")).isEqualTo(1);
        state(a, TaskStatus.TODO);
        dag.remove(actor, project, revision(), new Dependency(a.id(), b.id()));
        assertThat(count("notification")).isEqualTo(2);
    }

    @Test
    void removedAssigneeDoesNotReceiveUnblockedNotice() {
        Task a = task("A"), b = task("B", TaskStatus.TODO, other.userId());
        add(a, b);
        projects.member(actor, project, other.userId(), ProjectRole.MEMBER, false, revision());
        long before = count("notification");
        state(a, TaskStatus.DONE);
        assertThat(count("notification")).isEqualTo(before);
        assertThat(tasks.get(actor, project, b.id()).isBlocked()).isFalse();
    }

    @Test
    void noAssigneeMeansZeroUnblockNotificationsAndTaskNoOpDoesNotAdvanceVersions() {
        Task a = task("A"), b = task("B");
        add(a, b);
        var before = counts();
        state(a, TaskStatus.TODO);
        assertThat(counts()).isEqualTo(before);
        state(a, TaskStatus.DONE);
        assertThat(count("notification")).isZero();
    }

    @Test
    void invalidEdgesAndStaleRevisionNeverChangeFacts() {
        Task a = task("A"), b = task("B");
        assertThatThrownBy(() -> add(a, a)).satisfies(e -> expectCode(e, "DEPENDENCY_SELF_LOOP"));
        add(a, b);
        var before = counts();
        assertThatThrownBy(() -> add(a, b)).satisfies(e -> expectCode(e, "DEPENDENCY_EXISTS"));
        assertThatThrownBy(() -> add(b, a))
                .satisfies(
                        e -> {
                            expectCode(e, "DEPENDENCY_CYCLE");
                            assertThat(((ApiException) e).path)
                                    .containsExactly(b.id(), a.id(), b.id());
                        });
        assertThatThrownBy(
                        () ->
                                dag.add(
                                        actor,
                                        project,
                                        0,
                                        new Dependency(b.id(), a.id()),
                                        UUID.randomUUID().toString()))
                .satisfies(e -> expectCode(e, "PROJECT_REVISION_CONFLICT"));
        var another = projects.create(actor, workspace, "other", UUID.randomUUID().toString());
        Task foreign =
                tasks.create(
                        actor,
                        another.id(),
                        new TaskRequests.Create("foreign", null, null, null, null, null, null),
                        UUID.randomUUID().toString());
        assertThatThrownBy(() -> add(a, foreign))
                .satisfies(e -> expectCode(e, "PROJECT_SCOPE_ERROR"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void finalGraphRemovesAllOldEdgesBeforeAddingAndRollsBackInvalidLastAddition() {
        Task a = task("A"), b = task("B"), c = task("C");
        add(a, b);
        add(b, c);
        long old = revision();
        dag.replace(
                actor,
                project,
                old,
                List.of(new Dependency(a.id(), b.id())),
                List.of(new Dependency(c.id(), a.id())));
        assertThat(revision()).isEqualTo(old + 1);
        assertThat(edges.exists(project, new Dependency(c.id(), a.id()))).isTrue();
        var before = counts();
        assertThatThrownBy(
                        () ->
                                dag.replace(
                                        actor,
                                        project,
                                        revision(),
                                        List.of(new Dependency(c.id(), a.id())),
                                        List.of(
                                                new Dependency(a.id(), b.id()),
                                                new Dependency(c.id(), a.id()))))
                .satisfies(e -> expectCode(e, "VALIDATION_ERROR"));
        // Actual last-item cycle after an earlier insert, with no contradictory operation.
        assertThatThrownBy(
                        () ->
                                dag.replace(
                                        actor,
                                        project,
                                        revision(),
                                        List.of(new Dependency(b.id(), c.id())),
                                        List.of(
                                                new Dependency(a.id(), b.id()),
                                                new Dependency(b.id(), a.id()))))
                .satisfies(e -> expectCode(e, "DEPENDENCY_CYCLE"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void durableCreationReceiptAndNullablePatch() {
        var request =
                new TaskRequests.Create(
                        "idempotent",
                        null,
                        null,
                        null,
                        null,
                        other.userId(),
                        java.time.LocalDate.of(2026, 10, 1));
        String key = UUID.randomUUID().toString();
        Task a = tasks.create(actor, project, request, key);
        var before = counts();
        assertThat(tasks.create(actor, project, request, key).id()).isEqualTo(a.id());
        assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        actor,
                                        project,
                                        new TaskRequests.Create(
                                                "different", null, null, null, null, null, null),
                                        key))
                .satisfies(e -> expectCode(e, "IDEMPOTENCY_CONFLICT"));
        var patch = new TaskRequests.Patch();
        patch.setAssigneeId(null);
        patch.setDueDate(null);
        Task updated = tasks.update(actor, project, a.id(), a.revision(), patch);
        assertThat(updated.assigneeId()).isNull();
        assertThat(updated.dueDate()).isNull();
        assertThat(updated.revision()).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE project_id=?",
                Long.class,
                bytes(project));
    }
}
