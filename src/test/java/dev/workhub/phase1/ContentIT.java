package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

class ContentIT extends Phase1Support {
    @Autowired ContentService content;

    @Test
    void commentDeletionRequiresAuthorOrMaintainerAndPreservesAudit() {
        Task task = task("comment permissions");
        var aliceComment =
                content.createComment(
                        actor, project, task.id(), "Alice", UUID.randomUUID().toString());
        var before = counts();
        assertThatThrownBy(
                        () ->
                                content.deleteComment(
                                        other, project, task.id(), aliceComment.id(), 0))
                .satisfies(e -> expectCode(e, "FORBIDDEN"));
        assertThat(counts()).isEqualTo(before);
        assertThat(content.comments(other, project, task.id(), null, 100))
                .extracting(ContentRepository.Comment::body)
                .contains("Alice");

        var own =
                content.createComment(
                        other, project, task.id(), "Bob own", UUID.randomUUID().toString());
        content.deleteComment(other, project, task.id(), own.id(), 0);
        var moderated =
                content.createComment(
                        other, project, task.id(), "Bob moderation", UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                content.editComment(
                                        actor, project, task.id(), moderated.id(), 0, "rewrite"))
                .satisfies(e -> expectCode(e, "FORBIDDEN"));
        content.editComment(other, project, task.id(), moderated.id(), 0, "latest");
        assertThatThrownBy(
                        () -> content.deleteComment(actor, project, task.id(), moderated.id(), 0))
                .satisfies(e -> expectCode(e, "COMMENT_REVISION_CONFLICT"));
        content.deleteComment(actor, project, task.id(), moderated.id(), 1);
        assertThat(content.comments(actor, project, task.id(), null, 100))
                .extracting(ContentRepository.Comment::id)
                .containsExactly(aliceComment.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM activity WHERE project_id=? AND"
                                        + " kind='COMMENT_DELETED'",
                                Long.class,
                                bytes(project)))
                .isEqualTo(2);
        assertThat(
                        jdbc.<UUID>queryForObject(
                                "SELECT author_id FROM comment WHERE id=?",
                                (r, n) -> read(r, "author_id"),
                                bytes(moderated.id())))
                .isEqualTo(other.userId());
    }

    @Test
    void ownerVersionsAndDiscussionChangeWithoutProjectRevision() {
        Task task = task("task");
        long business = revision();
        String key = UUID.randomUUID().toString();
        var doc = content.createDocument(actor, project, null, "Plan", "body", key);
        var counts = counts();
        assertThat(content.createDocument(actor, project, null, "Plan", "body", key).id())
                .isEqualTo(doc.id());
        assertThat(counts()).isEqualTo(counts);
        var edited = content.editDocument(actor, project, doc.id(), 0, "Plan", "updated");
        assertThat(edited.revision()).isEqualTo(1);
        assertThatThrownBy(() -> content.editDocument(actor, project, doc.id(), 0, "Plan", "stale"))
                .satisfies(e -> expectCode(e, "DOCUMENT_REVISION_CONFLICT"));
        var comment =
                content.createComment(
                        actor, project, task.id(), "hello", UUID.randomUUID().toString());
        content.editComment(actor, project, task.id(), comment.id(), 0, "edited");
        assertThat(tasks.get(actor, project, task.id()).discussionRevision()).isEqualTo(2);
        assertThat(tasks.get(actor, project, task.id()).revision()).isZero();
        assertThat(revision()).isEqualTo(business);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_revision FROM knowledge_source WHERE document_id=?",
                                Long.class,
                                bytes(doc.id())))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_job WHERE project_id=? AND"
                                        + " status='WAITING'",
                                Long.class,
                                bytes(project)))
                .isEqualTo(5);
        assertThat(content.comments(actor, project, task.id(), null, 100)).hasSize(1);
    }

    @Test
    void mentionAndSubscriptionMergeSelfSuppressionAndExplicitUnsubscribe() {
        Task task = task("assigned", TaskStatus.TODO, other.userId());
        long initial = counts().get("notification");
        String body = "hello @u" + other.userId();
        String key = UUID.randomUUID().toString();
        var comment = content.createComment(actor, project, task.id(), body, key);
        assertThat(counts().get("notification")).isEqualTo(initial + 1);
        String reasons =
                jdbc.queryForObject(
                        "SELECT reason_set FROM notification WHERE project_id=? ORDER BY id DESC"
                                + " LIMIT 1",
                        String.class,
                        bytes(project));
        assertThat(reasons).contains("MENTION", "SUBSCRIPTION");
        content.createComment(actor, project, task.id(), body, key);
        assertThat(counts().get("notification")).isEqualTo(initial + 1);
        content.subscribe(other, project, task.id(), false);
        content.createComment(
                actor, project, task.id(), "no mention", UUID.randomUUID().toString());
        assertThat(counts().get("notification")).isEqualTo(initial + 1);
        content.createComment(
                other, project, task.id(), "I participated again", UUID.randomUUID().toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM task_subscription WHERE project_id=? AND"
                                        + " task_id=? AND user_id=?",
                                Boolean.class,
                                bytes(project),
                                bytes(task.id()),
                                bytes(other.userId())))
                .isFalse();
        projects.member(actor, project, other.userId(), ProjectRole.MEMBER, false, revision());
        assertThatThrownBy(() -> activity.notifications(other, project, Long.MAX_VALUE, 100))
                .satisfies(e -> expectCode(e, "FORBIDDEN"));
    }

    @Test
    void documentAndUrlLinksStayInProjectAndNeverGateDone() {
        Task task = task("done", TaskStatus.DONE, null);
        var doc =
                content.createDocument(
                        actor,
                        project,
                        null,
                        "Report",
                        "no certification",
                        UUID.randomUUID().toString());
        content.linkDocument(actor, project, task.id(), 0, doc.id(), true);
        UUID resource =
                content.linkUrl(actor, project, task.id(), 1, "https://example.com/results");
        assertThat(content.links(actor, project, task.id()).documents()).containsExactly(doc.id());
        assertThat(content.links(actor, project, task.id()).resources())
                .extracting(ContentRepository.Resource::id)
                .containsExactly(resource);
        assertThat(tasks.get(actor, project, task.id()).status()).isEqualTo(TaskStatus.DONE);
        var foreignProject =
                projects.create(actor, workspace, "foreign", UUID.randomUUID().toString());
        var foreign =
                content.createDocument(
                        actor,
                        foreignProject.id(),
                        null,
                        "foreign",
                        "body",
                        UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                content.linkDocument(
                                        actor, project, task.id(), 2, foreign.id(), true))
                .satisfies(e -> expectCode(e, "NOT_FOUND"));
        assertThatThrownBy(
                        () -> content.linkUrl(actor, project, task.id(), 2, "file:///etc/passwd"))
                .satisfies(e -> expectCode(e, "VALIDATION_ERROR"));
        content.unlinkResource(actor, project, task.id(), 2, resource);
        content.linkDocument(actor, project, task.id(), 3, doc.id(), false);
        assertThat(content.links(actor, project, task.id()).documents()).isEmpty();
    }
}
