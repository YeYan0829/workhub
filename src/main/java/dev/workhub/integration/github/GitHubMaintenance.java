package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import dev.workhub.messaging.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Polling registers obligations only; ordinary work is always executed from Rabbit deliveries. */
@Component
public class GitHubMaintenance {
    private final GitHubStore store;
    private final GitHubService service;
    private final WorkJobRepository jobs;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final dev.workhub.activity.ActivityService activity;

    public GitHubMaintenance(
            GitHubStore store,
            GitHubService service,
            WorkJobRepository jobs,
            OutboxRepository outbox,
            dev.workhub.activity.ActivityService activity,
            PlatformTransactionManager manager,
            @Value("${workhub.github.enabled:false}") boolean enabled) {
        this.store = store;
        this.service = service;
        this.jobs = jobs;
        this.outbox = outbox;
        this.enabled = enabled;
        this.activity = activity;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        tx.setTimeout(30);
    }

    @Scheduled(fixedDelayString = "${workhub.github.maintenance-ms:1000}")
    public void tick() {
        if (enabled) {
            recoverExpired();
            registerDue();
            notifyErrors();
        }
    }

    public void recoverExpired() {
        var ids =
                store.jdbc()
                        .query(
                                "SELECT id FROM work_job WHERE handler='GITHUB_WORK' AND"
                                    + " status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6) LIMIT"
                                    + " 100",
                                (r, n) -> uuid(r.getBytes(1)));
        for (UUID id : ids)
            tx.executeWithoutResult(
                    s -> {
                        var initial = jobs.find(id, false);
                        store.lockProject(initial.project());
                        // Recovery changes transport only. A reclaimed DISPATCH sees SEND_STARTED
                        // and only verifies.
                        var j = jobs.find(id, true);
                        Integer expired =
                                store.jdbc()
                                        .queryForObject(
                                                "SELECT COUNT(*) FROM work_job WHERE id=? AND"
                                                        + " status='RUNNING' AND"
                                                        + " lease_until<=UTC_TIMESTAMP(6)",
                                                Integer.class,
                                                bytes(id));
                        if (expired != 1) return;
                        var old = outbox.find(outbox.current(id, j.sequence()), true);
                        UUID next = UUID.randomUUID();
                        outbox.insert(
                                next,
                                id,
                                j.sequence() + 1,
                                old.destination(),
                                old.handler(),
                                old.wire(),
                                store.now());
                        jobs.retry(
                                j, j.sequence() + 1, store.now(), "EXECUTION_LEASE_EXPIRED", false);
                        outbox.disposition(
                                old.id(), "RETRY_SCHEDULED", next, "EXECUTION_LEASE_EXPIRED");
                    });
    }

    public void registerDue() {
        var ids =
                store.jdbc()
                        .query(
                                "SELECT id FROM github_connection WHERE enabled AND"
                                        + " (next_scan_at<=UTC_TIMESTAMP(6) OR"
                                        + " next_audit_at<=UTC_TIMESTAMP(6)) LIMIT 20",
                                (r, n) -> uuid(r.getBytes(1)));
        for (UUID id : ids)
            tx.executeWithoutResult(
                    s -> {
                        var c = store.connection(id);
                        store.lockProject(c.project());
                        c = store.connection(id);
                        if (!store.writable(c)) return;
                        int scan =
                                store.jdbc()
                                        .update(
                                                "UPDATE github_connection SET"
                                                    + " next_scan_at=TIMESTAMPADD(SECOND,300,UTC_TIMESTAMP(6))"
                                                    + " WHERE id=? AND"
                                                    + " next_scan_at<=UTC_TIMESTAMP(6)",
                                                bytes(id));
                        if (scan == 1) service.scan(c);
                        int audit =
                                store.jdbc()
                                        .update(
                                                "UPDATE github_connection SET"
                                                    + " next_audit_at=TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6))"
                                                    + " WHERE id=? AND"
                                                    + " next_audit_at<=UTC_TIMESTAMP(6)",
                                                bytes(id));
                        if (audit == 1) {
                            UUID round = UUID.randomUUID();
                            store.job(
                                    c.project(),
                                    c.id(),
                                    "github/audit/" + round,
                                    Map.of("stage", "AUDIT", "round", round),
                                    store.now());
                        }
                    });
        // Low-frequency read recovery includes unknown outcomes even after the active window ended.
        var errors =
                store.jdbc()
                        .query(
                                "SELECT resource_id FROM github_issue i WHERE"
                                    + " next_recovery_at<=UTC_TIMESTAMP(6) AND (error_code IS NOT"
                                    + " NULL OR EXISTS (SELECT 1 FROM sync_intent s WHERE"
                                    + " s.resource_id=i.resource_id AND s.state='ERROR' AND"
                                    + " s.send_started_at IS NOT NULL)) LIMIT 100",
                                (r, n) -> uuid(r.getBytes(1)));
        for (UUID id : errors)
            tx.executeWithoutResult(
                    s -> {
                        var i = store.issue(id, false);
                        store.lockProject(i.project());
                        if (store.jdbc()
                                        .update(
                                                "UPDATE github_issue SET"
                                                    + " next_recovery_at=TIMESTAMPADD(SECOND,300,UTC_TIMESTAMP(6))"
                                                    + " WHERE resource_id=? AND"
                                                    + " next_recovery_at<=UTC_TIMESTAMP(6)",
                                                bytes(id))
                                == 1) store.refresh(id);
                    });
    }

    public void notifyErrors() {
        var ids =
                store.jdbc()
                        .query(
                                "SELECT resource_id FROM github_issue WHERE error_code IS NOT NULL"
                                        + " AND NOT error_notified AND"
                                        + " error_since<=TIMESTAMPADD(SECOND,-300,UTC_TIMESTAMP(6))"
                                        + " LIMIT 100",
                                (r, n) -> uuid(r.getBytes(1)));
        for (UUID id : ids)
            tx.executeWithoutResult(
                    s -> {
                        var i = store.issue(id, false);
                        store.lockProject(i.project());
                        i = store.issue(id, true);
                        if (store.jdbc()
                                        .update(
                                                "UPDATE github_issue SET error_notified=TRUE WHERE"
                                                    + " resource_id=? AND error_code IS NOT NULL"
                                                    + " AND NOT error_notified AND"
                                                    + " error_since<=TIMESTAMPADD(SECOND,-300,UTC_TIMESTAMP(6))",
                                                bytes(id))
                                == 0) return;
                        var actor =
                                new dev.workhub.identity.ActorContext(
                                        store.connection(i.connection()).actor(), "GITHUB_SYNC");
                        long fact =
                                activity.record(
                                        i.project(),
                                        i.task(),
                                        actor,
                                        UUID.randomUUID(),
                                        "SYNC_ERROR",
                                        null,
                                        Map.of("resourceId", id, "error", i.error()));
                        Map<UUID, Set<String>> recipients = new HashMap<>();
                        store.jdbc()
                                .query(
                                        "SELECT user_id FROM project_member WHERE project_id=? AND"
                                                + " active AND role='MAINTAINER'",
                                        (r, n) -> uuid(r.getBytes(1)),
                                        bytes(i.project()))
                                .forEach(u -> recipients.put(u, Set.of("SYNC_ERROR")));
                        activity.notify(i.project(), fact, actor, recipients);
                    });
    }
}
