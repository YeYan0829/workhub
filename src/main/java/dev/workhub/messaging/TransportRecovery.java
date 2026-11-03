package dev.workhub.messaging;

import static dev.workhub.common.Ids.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Explicit broker-data-loss recovery only. Never refresh healthy backlog based on its age. */
@Component
public class TransportRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final WorkJobRepository jobs;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final String configuredEpoch;

    public TransportRecovery(
            JdbcTemplate jdbc,
            WorkJobRepository jobs,
            OutboxRepository outbox,
            PlatformTransactionManager manager,
            @Value("${workhub.messaging.recovery-epoch:}") String configuredEpoch) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.outbox = outbox;
        this.configuredEpoch = configuredEpoch;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!configuredEpoch.isBlank()) recover(UUID.fromString(configuredEpoch));
    }

    public void recover(UUID epoch) {
        UUID after = null;
        while (true) {
            var page = jobs.unfinished(after, 100);
            if (page.isEmpty()) return;
            for (UUID id : page) recoverOne(epoch, id);
            after = page.get(page.size() - 1);
        }
    }

    public void recoverOne(UUID epoch, UUID id) {
        var initial = jobs.find(id, false);
        if (initial == null) return;
        tx.executeWithoutResult(
                s -> {
                    jobs.lockProject(initial);
                    var job = jobs.find(id, true);
                    if (!(job.status().equals("WAITING") || job.status().equals("RETRY_WAIT")))
                        return;
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM transport_recovery WHERE epoch=? AND"
                                            + " job_id=?",
                                    Long.class,
                                    bytes(epoch),
                                    bytes(id))
                            > 0) return;
                    UUID oldId = outbox.current(id, job.sequence());
                    if (oldId == null)
                        throw new IllegalStateException("Missing transport obligation for " + id);
                    var old = outbox.find(oldId, true);
                    UUID next = UUID.randomUUID();
                    outbox.insert(
                            next,
                            id,
                            job.sequence() + 1,
                            old.destination(),
                            old.handler(),
                            old.wire(),
                            job.nextAttempt());
                    jobs.retry(
                            job,
                            job.sequence() + 1,
                            job.nextAttempt(),
                            "TRANSPORT_RECOVERY",
                            false);
                    outbox.disposition(oldId, "SUPERSEDED", null, "TRANSPORT_RECOVERY");
                    jdbc.update(
                            "INSERT INTO transport_recovery(epoch,job_id,message_id) VALUES"
                                    + " (?,?,?)",
                            bytes(epoch),
                            bytes(id),
                            bytes(next));
                });
    }
}
