package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.JsonNode;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.ActorContext;
import dev.workhub.knowledge.SourceObligations;
import dev.workhub.work.SubscriptionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class GitHubComments {
    private final GitHubStore store;
    private final SourceObligations sources;
    private final ActivityService activity;
    private final SubscriptionRepository subscriptions;

    public GitHubComments(
            GitHubStore store,
            SourceObligations sources,
            ActivityService activity,
            SubscriptionRepository subscriptions) {
        this.store = store;
        this.sources = sources;
        this.activity = activity;
        this.subscriptions = subscriptions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(GitHubStore.Issue issue, JsonNode rows, String round, boolean initial) {
        var actor = new ActorContext(store.connection(issue.connection()).actor(), "GITHUB_SYNC");
        for (JsonNode c : rows) {
            long remote = c.path("id").asLong();
            if (remote <= 0) throw new IllegalArgumentException("Invalid comment identity");
            Instant updated = Instant.parse(c.path("updated_at").asText());
            String body = c.path("body").asText("");
            store.jdbc()
                    .update(
                            "INSERT IGNORE INTO"
                                    + " github_comment_seen(resource_id,round_key,remote_id) VALUES"
                                    + " (?,?,?)",
                            bytes(issue.resource()),
                            round,
                            remote);
            var old =
                    store.jdbc()
                            .queryForList(
                                    "SELECT id,body,remote_updated_at,active FROM github_comment"
                                            + " WHERE resource_id=? AND remote_id=?",
                                    bytes(issue.resource()),
                                    remote);
            UUID id = old.isEmpty() ? UUID.randomUUID() : uuid((byte[]) old.get(0).get("id"));
            if (!old.isEmpty()) {
                Instant before = GitHubStore.instant(old.get(0).get("remote_updated_at"));
                if (updated.isBefore(before)) continue;
                if (body.equals(old.get(0).get("body"))
                        && Boolean.TRUE.equals(old.get(0).get("active"))) {
                    store.jdbc()
                            .update(
                                    "UPDATE github_comment SET remote_updated_at=?,remote_login=?"
                                            + " WHERE id=?",
                                    Utc.value(updated),
                                    c.path("user").path("login").asText(),
                                    bytes(id));
                    continue;
                }
            }
            store.jdbc()
                    .update(
                            "INSERT INTO"
                                + " github_comment(id,project_id,resource_id,remote_id,body,remote_login,remote_updated_at)"
                                + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                                + " body=VALUES(body),remote_login=VALUES(remote_login),remote_updated_at=VALUES(remote_updated_at),revision=revision+1,active=TRUE",
                            bytes(id),
                            bytes(issue.project()),
                            bytes(issue.resource()),
                            remote,
                            body,
                            c.path("user").path("login").asText(),
                            Utc.value(updated));
            sources.changed(
                    issue.project(),
                    id,
                    SourceObligations.Owner.GITHUB_COMMENT,
                    body,
                    c.path("html_url")
                            .asText(
                                    "https://github.com/"
                                            + store.connection(issue.connection()).name()
                                            + "/issues/"
                                            + issue.number()
                                            + "#issuecomment-"
                                            + remote));
            store.jdbc()
                    .update(
                            "UPDATE knowledge_source SET active=TRUE WHERE github_comment_id=?",
                            bytes(id));
            if (issue.task() != null) {
                store.jdbc()
                        .update(
                                "UPDATE task SET discussion_revision=discussion_revision+1 WHERE"
                                        + " id=?",
                                bytes(issue.task()));
                if (!initial) {
                    UUID cause = UUID.randomUUID();
                    long fact =
                            activity.record(
                                    issue.project(),
                                    issue.task(),
                                    actor,
                                    cause,
                                    "GITHUB_COMMENT_OBSERVED",
                                    null,
                                    Map.of("remoteId", remote, "revisionAt", updated.toString()));
                    if (old.isEmpty()) {
                        Map<UUID, Set<String>> recipients = new HashMap<>();
                        subscriptions
                                .subscribers(issue.project(), issue.task())
                                .forEach(u -> recipients.put(u, Set.of("SUBSCRIBED_COMMENT")));
                        activity.notify(issue.project(), fact, actor, recipients);
                    }
                }
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(GitHubStore.Issue issue, String round, long captured) {
        if (issue.requested() == captured) {
            var deleted =
                    store.jdbc()
                            .query(
                                    "SELECT c.id FROM github_comment c WHERE c.resource_id=? AND"
                                        + " c.active AND NOT EXISTS (SELECT 1 FROM"
                                        + " github_comment_seen s WHERE s.resource_id=c.resource_id"
                                        + " AND s.round_key=? AND s.remote_id=c.remote_id)",
                                    (r, n) -> uuid(r.getBytes(1)),
                                    bytes(issue.resource()),
                                    round);
            for (UUID id : deleted) {
                sources.changed(
                        issue.project(),
                        id,
                        SourceObligations.Owner.GITHUB_COMMENT,
                        "",
                        "https://github.com/"
                                + store.connection(issue.connection()).name()
                                + "/issues/"
                                + issue.number());
                store.jdbc()
                        .update(
                                "UPDATE knowledge_source SET active=FALSE WHERE"
                                        + " github_comment_id=?",
                                bytes(id));
                store.jdbc()
                        .update(
                                "UPDATE github_comment SET active=FALSE,revision=revision+1 WHERE"
                                        + " id=?",
                                bytes(id));
            }
            if (!deleted.isEmpty() && issue.task() != null)
                store.jdbc()
                        .update(
                                "UPDATE task SET discussion_revision=discussion_revision+1 WHERE"
                                        + " id=?",
                                bytes(issue.task()));
        }
        store.jdbc()
                .update(
                        "DELETE FROM github_comment_seen WHERE resource_id=? AND round_key=?",
                        bytes(issue.resource()),
                        round);
    }
}
