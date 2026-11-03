package dev.workhub.phase2;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import com.rabbitmq.client.*;

import dev.workhub.common.*;
import dev.workhub.contract.ContractSupport;
import dev.workhub.messaging.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

@SpringBootTest(
        properties = {"workhub.messaging.enabled=false", "workhub.bootstrap.login=", "debug=false"})
@ImportTestHandlers
abstract class Phase2Support {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", Phase2Support::url);
        p.add("spring.rabbitmq.virtual-host", () -> "phase2_test");
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
    }

    static String url() {
        return "jdbc:mysql://127.0.0.1:"
                + ContractSupport.env("MYSQL_PORT", "13306")
                + "/workhub_phase2_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired WorkJobRepository jobs;
    @Autowired WorkJobExecutor executor;
    @Autowired RabbitPublisher rabbitPublisher;
    @Autowired RetryPolicy retry;
    @Autowired TransportRecovery recovery;
    @Autowired Json json;
    @Autowired PlatformTransactionManager manager;
    @Autowired LocalHandler handler;
    Connection connection;
    Channel channel;
    UUID project;

    @BeforeEach
    void setup() throws Exception {
        // Only this isolated test schema is mutated. Never application or Phase 0/1 data.
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_phase2_test");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS phase2_effect(id BIGINT AUTO_INCREMENT PRIMARY"
                    + " KEY,job_id BINARY(16) NOT NULL)");
        jdbc.update("DELETE FROM transport_recovery");
        jdbc.update("DELETE FROM failure_record");
        jdbc.update(
                "UPDATE outbox_message SET"
                    + " consume_disposition='SUPERSEDED',handled_at=UTC_TIMESTAMP(6),next_message_id=NULL");
        jdbc.update("DELETE FROM outbox_message");
        jdbc.update("DELETE FROM phase2_effect");
        jdbc.update("DELETE FROM work_job");
        UUID workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO workspace(id,name) VALUES (?,'phase2')", bytes(workspace));
        jdbc.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'phase2')",
                bytes(project),
                bytes(workspace));
        handler.failures.set(0);
        handler.permanent = false;
        handler.crash = false;
        connection = factory().newConnection("phase2-test");
        channel = connection.createChannel();
        topology(channel);
        channel.queuePurge(RabbitConfiguration.QUEUE);
        channel.queuePurge(RabbitConfiguration.PARKING);
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null && connection.isOpen()) connection.close();
    }

    static ConnectionFactory factory() {
        var f = new ConnectionFactory();
        f.setHost("127.0.0.1");
        f.setPort(Integer.parseInt(ContractSupport.env("RABBITMQ_PORT", "15672")));
        f.setUsername(ContractSupport.env("RABBITMQ_USER", "workhub"));
        f.setPassword(ContractSupport.required("RABBITMQ_PASSWORD"));
        f.setVirtualHost("phase2_test");
        f.setAutomaticRecoveryEnabled(false);
        f.setConnectionTimeout(3000);
        return f;
    }

    static void topology(Channel ch) throws Exception {
        ch.exchangeDeclare(RabbitConfiguration.EXCHANGE, "direct", true);
        for (String q : List.of(RabbitConfiguration.QUEUE, RabbitConfiguration.PARKING)) {
            ch.queueDeclare(q, true, false, false, null);
            ch.queueBind(q, RabbitConfiguration.EXCHANGE, q);
        }
    }

    OutboxPublisher publisher() {
        return new OutboxPublisher(outbox, rabbitPublisher, retry);
    }

    OutboxRepository.Envelope seed() {
        return seed("TEST");
    }

    OutboxRepository.Envelope seed(String kind) {
        UUID id = UUID.randomUUID(), message = UUID.randomUUID();
        String payload = "{}", hash = Inputs.hash(payload);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s -> {
                            jdbc.update(
                                    "INSERT INTO"
                                        + " work_job(id,kind,handler,job_key,project_id,owner_ref,payload,payload_hash)"
                                        + " VALUES (?,?,?,?,?,?,?,?)",
                                    bytes(id),
                                    kind,
                                    kind,
                                    "phase2/" + id,
                                    bytes(project),
                                    bytes(id),
                                    payload,
                                    hash);
                            String wire =
                                    json.write(
                                            Map.of(
                                                    "jobId",
                                                    id,
                                                    "projectId",
                                                    project,
                                                    "kind",
                                                    kind,
                                                    "schemaVersion",
                                                    1,
                                                    "payloadHash",
                                                    hash));
                            outbox.insert(
                                    message,
                                    id,
                                    0,
                                    RabbitConfiguration.QUEUE,
                                    kind,
                                    wire,
                                    jobs.now());
                        });
        return outbox.find(message, false);
    }

    void execute(OutboxRepository.Envelope e) {
        executor.handle(
                e.id().toString(), e.destination(), e.wire().getBytes(StandardCharsets.UTF_8));
    }

    void due() {
        jdbc.update(
                "UPDATE work_job SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                    + " status='RETRY_WAIT'");
        jdbc.update(
                "UPDATE outbox_message SET"
                    + " available_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)),claim_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))");
    }

    long effects(UUID job) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM phase2_effect WHERE job_id=?", Long.class, bytes(job));
    }

    String status(UUID job) {
        return jobs.find(job, false).status();
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    GetResponse get(Channel ch) throws Exception {
        AtomicReference<GetResponse> value = new AtomicReference<>();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .until(
                        () -> {
                            value.set(ch.basicGet(RabbitConfiguration.QUEUE, false));
                            return value.get() != null;
                        });
        return value.get();
    }

    void deliver(GetResponse delivery, Channel ch) throws Exception {
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setMessageId(delivery.getProps().getMessageId());
        properties.setReceivedRoutingKey(delivery.getEnvelope().getRoutingKey());
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        new WorkJobConsumer(executor)
                .receive(
                        new org.springframework.amqp.core.Message(delivery.getBody(), properties),
                        ch);
    }

    @TestConfiguration
    static class Handlers {
        @Bean
        LocalHandler localHandler(JdbcTemplate jdbc) {
            return new LocalHandler(jdbc);
        }
    }

    static class LocalHandler implements WorkHandler {
        final JdbcTemplate jdbc;
        final AtomicInteger failures = new AtomicInteger();
        boolean permanent, crash;

        LocalHandler(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public String kind() {
            return "TEST";
        }

        public Result execute(WorkJobRepository.Job job) {
            jdbc.update("INSERT INTO phase2_effect(job_id) VALUES (?)", bytes(job.id()));
            if (crash) Runtime.getRuntime().halt(73);
            if (permanent) throw new WorkFailure(false, "TEST_PERMANENT");
            if (failures.getAndUpdate(v -> Math.max(0, v - 1)) > 0)
                throw new WorkFailure(true, "TEST_TRANSIENT");
            return new Result("test/" + job.id(), Inputs.hash("test/" + job.id()));
        }
    }
}
