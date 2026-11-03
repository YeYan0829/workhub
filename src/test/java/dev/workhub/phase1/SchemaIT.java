package dev.workhub.phase1;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.contract.ContractSupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.*;

class SchemaIT {
    static String url() {
        return "jdbc:mysql://127.0.0.1:"
                + ContractSupport.env("MYSQL_PORT", "13306")
                + "/"
                + ContractSupport.env("MYSQL_DATABASE", "workhub")
                + "_phase1_test"
                + "?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC";
    }

    @Test
    void productionMigrationsAndDatabaseDefenses() throws Exception {
        var flyway =
                Flyway.configure()
                        .dataSource(
                                url(),
                                ContractSupport.env("MYSQL_USER", "workhub"),
                                ContractSupport.required("MYSQL_PASSWORD"))
                        .load();
        var migrated = flyway.migrate();
        System.out.println("PHASE1_SCHEMA migrationsExecuted=" + migrated.migrationsExecuted);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c =
                DriverManager.getConnection(
                        url(),
                        ContractSupport.env("MYSQL_USER", "workhub"),
                        ContractSupport.required("MYSQL_PASSWORD"))) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute(
                        "SET @u=UUID_TO_BIN(UUID()), @w=UUID_TO_BIN(UUID()),"
                                + " @p=UUID_TO_BIN(UUID()), @q=UUID_TO_BIN(UUID()),"
                                + " @a=UUID_TO_BIN(UUID()), @b=UUID_TO_BIN(UUID()),"
                                + " @c=UUID_TO_BIN(UUID())");
                s.execute(
                        "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                                + " (@u,UUID(),'test','unused')");
                s.execute("INSERT INTO workspace(id,name) VALUES (@w,'test')");
                s.execute(
                        "INSERT INTO project(id,workspace_id,name) VALUES (@p,@w,'P'),(@q,@w,'Q')");
                s.execute(
                        "INSERT INTO project_member(project_id,user_id,role) VALUES"
                                + " (@p,@u,'MAINTAINER')");
                s.execute(
                        "INSERT INTO"
                            + " task(id,project_id,title,description,status,type,origin,created_by)"
                            + " VALUES"
                            + " (@a,@p,'A','','TODO','TASK','MANUAL',@u),(@b,@p,'B','','TODO','TASK','MANUAL',@u),(@c,@q,'C','','TODO','TASK','MANUAL',@u)");
                String insert =
                        "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by)"
                                + " VALUES ";
                s.execute(insert + "(@p,@a,@b,@u)");
                assertThatThrownBy(() -> s.execute(insert + "(@p,@a,@b,@u)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute(insert + "(@p,@a,@a,@u)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute(insert + "(@p,@a,@c,@u)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute("DELETE FROM task WHERE id=@a"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute("UPDATE task SET status='BLOCKED' WHERE id=@a"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute("UPDATE task SET status='todo' WHERE id=@a"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> s.execute("UPDATE task SET assignee_id=@u WHERE id=@c"))
                        .isInstanceOf(SQLException.class);
                try (var r =
                        s.executeQuery(
                                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM"
                                    + " information_schema.statistics WHERE table_schema=DATABASE()"
                                    + " AND table_name='task_dependency' AND"
                                    + " index_name='PRIMARY'")) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo("project_id,blocker_id,blocked_id");
                }
            } finally {
                c.rollback();
            }
        }
    }
}
