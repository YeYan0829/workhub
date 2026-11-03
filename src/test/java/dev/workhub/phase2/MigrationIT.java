package dev.workhub.phase2;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.*;
import dev.workhub.contract.ContractSupport;
import dev.workhub.messaging.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import java.util.*;

class MigrationIT {
    @Test
    void upgradePreservesExistingPhase1ObligationAndItCanExecute() {
        String url =
                Phase2Support.url()
                        .replace("/workhub_phase2_test?", "/workhub_phase2_migration_test?");
        var ds =
                new DriverManagerDataSource(
                        url,
                        ContractSupport.env("MYSQL_USER", "workhub"),
                        ContractSupport.required("MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_phase2_migration_test");
        var old = Flyway.configure().dataSource(ds).target("3").cleanDisabled(false).load();
        old.clean();
        old.migrate();
        UUID job = UUID.randomUUID(), message = UUID.randomUUID();
        String hash = Inputs.hash("{}");
        jdbc.update(
                "INSERT INTO work_job(id,kind,handler,job_key,owner_ref,payload,payload_hash)"
                    + " VALUES (?,'TEST','TEST',?,?,'{}',?)",
                bytes(job),
                job.toString(),
                bytes(job),
                hash);
        String wire =
                "{\"jobId\":\""
                        + job
                        + "\",\"projectId\":null,\"kind\":\"TEST\",\"schemaVersion\":1,\"payloadHash\":\""
                        + hash
                        + "\"}";
        jdbc.update(
                "INSERT INTO"
                    + " outbox_message(id,job_id,dispatch_seq,destination,expected_handler,schema_version,payload,payload_hash)"
                    + " VALUES (?,?,0,'knowledge.index','TEST',1,?,?)",
                bytes(message),
                bytes(job),
                wire,
                Inputs.hash(wire));
        var upgraded = Flyway.configure().dataSource(ds).target("4").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        var outbox = new OutboxRepository(jdbc);
        var jobs = new WorkJobRepository(jdbc);
        var envelope = outbox.find(message, false);
        assertThat(envelope.hash()).isEqualTo(Inputs.hash(envelope.wire()));
        assertThat(envelope.jobId()).isEqualTo(job);
        assertThat(jobs.find(job, false).status()).isEqualTo("WAITING");
        jdbc.execute(
                "CREATE TABLE phase2_effect(id BIGINT AUTO_INCREMENT PRIMARY KEY,job_id BINARY(16)"
                    + " NOT NULL)");
        var handler = new Phase2Support.LocalHandler(jdbc);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var executor =
                new WorkJobExecutor(
                        jobs,
                        outbox,
                        new FailureRepository(jdbc, outbox, jobs, new Json(mapper)),
                        new EnvelopeValidator(mapper),
                        new HandlerRegistry(List.of(handler)),
                        new RetryPolicy(),
                        new DataSourceTransactionManager(ds));
        executor.handle(
                message.toString(),
                envelope.destination(),
                envelope.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jobs.find(job, false).status()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM phase2_effect", Long.class))
                .isEqualTo(1);
    }
}
