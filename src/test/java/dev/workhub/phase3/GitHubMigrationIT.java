package dev.workhub.phase3;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.Inputs;
import dev.workhub.contract.ContractSupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class GitHubMigrationIT {
    @Test
    void v4ObligationAndImmutableDoneSurviveUpgrade() {
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + ContractSupport.env("MYSQL_PORT", "13306")
                        + "/workhub_phase3_migration_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC";
        var ds =
                new DriverManagerDataSource(
                        url,
                        ContractSupport.env("MYSQL_USER", "workhub"),
                        ContractSupport.required("MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_phase3_migration_test");
        var old = Flyway.configure().dataSource(ds).target("4").cleanDisabled(false).load();
        old.clean();
        old.migrate();
        UUID job = UUID.randomUUID(), message = UUID.randomUUID();
        String hash = Inputs.hash("{}");
        jdbc.update(
                "INSERT INTO"
                    + " work_job(id,kind,handler,job_key,owner_ref,payload,payload_hash,status,completed_at,result_ref,result_hash)"
                    + " VALUES"
                    + " (?,'INDEX_TEXT','INDEX_TEXT',?,?,'{}',?,'DONE',UTC_TIMESTAMP(6),'original-receipt',?)",
                bytes(job),
                job.toString(),
                bytes(job),
                hash,
                hash);
        jdbc.update(
                "INSERT INTO"
                    + " outbox_message(id,job_id,dispatch_seq,destination,expected_handler,schema_version,payload,payload_hash,wire_payload,consume_disposition,handled_at)"
                    + " VALUES"
                    + " (?,?,0,'knowledge.index','INDEX_TEXT',1,'{}',?,'{}','SUCCEEDED',UTC_TIMESTAMP(6))",
                bytes(message),
                bytes(job),
                hash);
        var current = Flyway.configure().dataSource(ds).target("6").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_ref FROM work_job WHERE id=?",
                                String.class,
                                bytes(job)))
                .isEqualTo("original-receipt");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT consume_disposition FROM outbox_message WHERE id=?",
                                String.class,
                                bytes(message)))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM github_issue", Long.class)).isZero();
    }
}
