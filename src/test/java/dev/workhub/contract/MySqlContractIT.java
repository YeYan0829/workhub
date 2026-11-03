package dev.workhub.contract;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MySqlContractIT {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String node = "p0_node_" + suffix, edge = "p0_edge_" + suffix;
    private Connection db;
    private static byte[] id(long n) { return ByteBuffer.allocate(16).putLong(0).putLong(n).array(); }

    @BeforeEach void setup() throws Exception {
        db = ContractSupport.mysql();
        sql(db, "CREATE TABLE " + node + " (id BINARY(16) PRIMARY KEY, revision BIGINT NOT NULL) ENGINE=InnoDB");
        sql(db, "CREATE TABLE " + edge + " (project_id BINARY(16), blocker BINARY(16), blocked BINARY(16), PRIMARY KEY(project_id,blocker,blocked)) ENGINE=InnoDB");
        try (var p = db.prepareStatement("INSERT INTO " + node + " VALUES (?,0)")) { p.setBytes(1,id(1)); p.executeUpdate(); }
        db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        db.setAutoCommit(false);
    }
    @AfterEach void cleanup() throws Exception {
        if (db != null) {
            try {
                if(!db.getAutoCommit()) db.rollback();
                db.setAutoCommit(true);
                sql(db, "DROP TABLE IF EXISTS " + edge); sql(db, "DROP TABLE IF EXISTS " + node);
            } finally { db.close(); }
        }
    }
    static void sql(Connection c, String s) throws SQLException { try (var p=c.createStatement()) { p.execute(s); } }
    static long scalar(Connection c, String s) throws SQLException {
        try (var p=c.createStatement(); var r=p.executeQuery(s)) { r.next(); return r.getLong(1); }
    }
    void link(long a, long b) throws SQLException {
        try (var p=db.prepareStatement("INSERT INTO " + edge + " VALUES (?,?,?)")) {
            p.setBytes(1,id(99)); p.setBytes(2,id(a)); p.setBytes(3,id(b)); p.executeUpdate();
        }
    }
    Set<Long> reachable(long start) throws SQLException {
        var result = new HashSet<Long>();
        String query = "WITH RECURSIVE reach(id) AS (SELECT CAST(? AS BINARY(16)) UNION DISTINCT "
                + "SELECT e.blocked FROM " + edge + " e JOIN reach r ON e.blocker=r.id WHERE e.project_id=?) SELECT id FROM reach";
        try (var p=db.prepareStatement(query)) {
            p.setBytes(1,id(start)); p.setBytes(2,id(99));
            try (var r=p.executeQuery()) {
                while(r.next()) { byte[] v=r.getBytes(1); assertThat(v).hasSize(16); result.add(ByteBuffer.wrap(v).getLong(8)); }
            }
        }
        return result;
    }
    @Test void connectionIsolationAndRollback() throws Exception {
        assertThat(db.getMetaData().getDatabaseProductVersion()).startsWith("8.4.");
        assertThat(db.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        try(var s=db.createStatement();var r=s.executeQuery("SELECT @@transaction_isolation")) {
            r.next(); assertThat(r.getString(1)).isEqualTo("READ-COMMITTED");
        }
        sql(db,"UPDATE " + node + " SET revision=7"); db.rollback();
        assertThat(scalar(db,"SELECT revision FROM " + node)).isZero();
    }
    @Test void forUpdateSerializesTwoConnections() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection second=ContractSupport.mysql()) {
            second.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); second.setAutoCommit(false);
            sql(db,"SELECT * FROM " + node + " FOR UPDATE");
            CountDownLatch issuing=new CountDownLatch(1);
            Future<Long> waiter=executor.submit(() -> {
                try(var s=second.createStatement()) {
                    s.setQueryTimeout(5); issuing.countDown();
                    try(var r=s.executeQuery("SELECT revision FROM " + node + " FOR UPDATE")) { r.next(); return r.getLong(1); }
                }
            });
            assertThat(issuing.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            sql(db,"UPDATE " + node + " SET revision=1"); db.commit();
            assertThat(waiter.get(4,TimeUnit.SECONDS)).isEqualTo(1L); second.rollback();
        } finally { executor.shutdownNow(); }
    }
    @Test void staleRevisionUpdatesZeroRows() throws Exception {
        try(var p=db.prepareStatement("UPDATE " + node + " SET revision=revision+1 WHERE revision=?")) {
            p.setLong(1,0); assertThat(p.executeUpdate()).isEqualTo(1); db.commit();
            assertThat(p.executeUpdate()).isZero();
        }
    }
    @Test void binarySeedDiamondAndCycleTerminateWithDistinct() throws Exception {
        link(1,2); link(2,3);
        assertThat(reachable(1)).contains(3L); assertThat(reachable(3)).doesNotContain(1L);
        link(1,4); link(4,3);
        assertThat(reachable(1)).containsExactlyInAnyOrder(1L,2L,3L,4L);
        link(3,1); // Deliberately invalid graph: only a SQL recursion contract fixture.
        assertThat(reachable(1)).containsExactlyInAnyOrder(1L,2L,3L,4L);
    }
    @Test void recursionLimitRequiresExplicitRollbackAndSessionReset() throws Exception {
        long initial=scalar(db,"SELECT @@session.cte_max_recursion_depth");
        try {
            sql(db,"SET SESSION cte_max_recursion_depth=2");
            link(1,2);link(2,3);link(3,4);link(4,5);
            assertThatThrownBy(() -> reachable(1)).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException)e).getErrorCode()).isEqualTo(3636));
            // Statement failure did not automatically undo previous writes.
            assertThat(scalar(db,"SELECT COUNT(*) FROM " + edge)).isEqualTo(4);
            db.rollback(); assertThat(scalar(db,"SELECT COUNT(*) FROM " + edge)).isZero();
        } finally { sql(db,"SET SESSION cte_max_recursion_depth="+initial); }
        assertThat(scalar(db,"SELECT @@session.cte_max_recursion_depth")).isEqualTo(initial);
    }
    @Test void driverTimeoutRollsBackAndConnectionRemainsUsable() throws Exception {
        sql(db,"UPDATE " + node + " SET revision=8");
        try(var s=db.createStatement()) {
            s.setQueryTimeout(1);
            assertThatThrownBy(() -> s.executeQuery("SELECT SLEEP(10)")).isInstanceOf(SQLException.class);
        } finally { db.rollback(); }
        assertThat(db.isValid(2)).isTrue();
        assertThat(scalar(db,"SELECT revision FROM " + node)).isZero();
        assertThat(scalar(db,"SELECT 1")).isEqualTo(1);
    }
    @Test void pooledConnectionResetsIsolationAndExplicitlyRestoresSessionVariable() throws Exception {
        var config=new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(db.getMetaData().getURL());
        config.setUsername(ContractSupport.env("MYSQL_USER","workhub"));config.setPassword(ContractSupport.required("MYSQL_PASSWORD"));
        config.setMaximumPoolSize(1);config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        try(var pool=new com.zaxxer.hikari.HikariDataSource(config)) {
            long initial, physical;
            try(var borrowed=pool.getConnection()) {
                initial=scalar(borrowed,"SELECT @@session.cte_max_recursion_depth");
                physical=scalar(borrowed,"SELECT CONNECTION_ID()");
                try {
                    borrowed.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    sql(borrowed,"SET SESSION cte_max_recursion_depth=2");
                } finally {sql(borrowed,"SET SESSION cte_max_recursion_depth="+initial);}
            }
            try(var reused=pool.getConnection()) {
                assertThat(scalar(reused,"SELECT CONNECTION_ID()")).isEqualTo(physical);
                assertThat(reused.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(scalar(reused,"SELECT @@session.cte_max_recursion_depth")).isEqualTo(initial);
            }
        }
    }
    @Test void flywayExecutesAndValidatesIsolatedFixtureMigration() throws Exception {
        String probe="p0_probe_"+suffix, history="p0_history_"+suffix;
        var flyway=org.flywaydb.core.Flyway.configure()
            .dataSource(db.getMetaData().getURL(),ContractSupport.env("MYSQL_USER","workhub"),ContractSupport.required("MYSQL_PASSWORD"))
            .locations("classpath:db/contract").table(history).placeholders(Map.of("probeTable",probe))
            // Only this disposable fixture schema is baselined, never the application schema.
            .baselineOnMigrate(true).baselineVersion("0").load();
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(scalar(db,"SELECT COUNT(*) FROM " + probe)).isEqualTo(1);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            db.rollback();db.setAutoCommit(true);
            sql(db,"DROP TABLE IF EXISTS " + probe);sql(db,"DROP TABLE IF EXISTS " + history);
        }
    }
}
