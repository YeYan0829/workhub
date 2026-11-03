package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.ApiException;
import dev.workhub.importing.CsvFormat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

class MigrationAndLimitsIT {
    @Test
    void emptyDatabaseAndV6UpgradePreserveExistingIdentityAndAuthors() {
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + System.getenv().getOrDefault("MYSQL_PORT", "13306")
                        + "/workhub_phase3x_migration_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC";
        var ds =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("MYSQL_USER", "workhub"),
                        System.getenv("MYSQL_PASSWORD"));
        var db = new JdbcTemplate(ds);
        assertThat(db.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_phase3x_migration_test");
        var old = Flyway.configure().dataSource(ds).target("6").cleanDisabled(false).load();
        old.clean();
        old.migrate();
        UUID user = UUID.randomUUID(),
                w = UUID.randomUUID(),
                p = UUID.randomUUID(),
                task = UUID.randomUUID();
        db.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                        + " (?,'legacy','Legacy','unchanged')",
                bytes(user));
        db.update("INSERT INTO workspace(id,name) VALUES (?,'Legacy')", bytes(w));
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",
                bytes(w),
                bytes(user));
        db.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Legacy')",
                bytes(p),
                bytes(w));
        db.update(
                "INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MAINTAINER')",
                bytes(p),
                bytes(user));
        db.update(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,'Old','','TRIAGE','TASK','MANUAL',?)",
                bytes(task),
                bytes(p),
                bytes(user));
        var current = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        int pendingUpgrade = current.info().pending().length;
        assertThat(pendingUpgrade).isPositive();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(pendingUpgrade);
        assertThat(current.info().pending()).isEmpty();
        assertThat(
                        db.queryForObject(
                                "SELECT password_hash FROM app_user WHERE id=?",
                                String.class,
                                bytes(user)))
                .isEqualTo("unchanged");
        assertThat(
                        db.queryForObject(
                                "SELECT role FROM workspace_member WHERE workspace_id=?",
                                String.class,
                                bytes(w)))
                .isEqualTo("ADMIN");
        assertThat(
                        db.<UUID>queryForObject(
                                "SELECT created_by FROM task WHERE id=?",
                                (r, n) -> read(r, "created_by"),
                                bytes(task)))
                .isEqualTo(user);
        assertThat(
                        db.queryForObject(
                                "SELECT triage_required FROM task WHERE id=?",
                                Boolean.class,
                                bytes(task)))
                .isTrue();
        assertThat(current.migrate().migrationsExecuted).isZero();
        current.clean();
        int pendingFresh = current.info().pending().length;
        assertThat(pendingFresh).isEqualTo(pendingUpgrade + 6);
        assertThat(current.migrate().migrationsExecuted).isEqualTo(pendingFresh);
        assertThat(current.info().pending()).isEmpty();
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void strictCsvResourceAndQuotingBoundaries() {
        String h = String.join(",", CsvFormat.HEADER) + "\n";
        var rows =
                CsvFormat.parse((h + "\"comma, newline\nquote\"\"\",body,TASK,2,,\n").getBytes());
        assertThat(rows.get(0).get(0)).isEqualTo("comma, newline\nquote\"");
        assertThatThrownBy(() -> CsvFormat.parse(new byte[1_048_577]))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CsvFormat.parse((h + "x,,TASK,2,,\n".repeat(1001)).getBytes()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CsvFormat.parse(new byte[] {(byte) 0xFF}))
                .isInstanceOf(ApiException.class);
    }
}
