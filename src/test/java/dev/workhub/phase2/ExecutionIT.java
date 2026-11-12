package dev.workhub.phase2;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.knowledge.SourceObligations;
import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class ExecutionIT extends Phase2Support {
    @Test
    void twoActualAmqpConsumersAcknowledgeDuplicateDeliveriesWithOneBusinessEffect()
            throws Exception {
        var envelope = seed();
        var start = new java.util.concurrent.CyclicBarrier(2);
        var done = new java.util.concurrent.CountDownLatch(2);
        var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var threads = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var consumer = new WorkJobConsumer(executor);
        try (var first = connection.createChannel();
                var second = connection.createChannel()) {
            com.rabbitmq.client.DeliverCallback callback =
                    (tag, delivery) -> {
                        try {
                            threads.add(Thread.currentThread().getName());
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            var properties = new org.springframework.amqp.core.MessageProperties();
                            properties.setMessageId(delivery.getProperties().getMessageId());
                            properties.setReceivedRoutingKey(
                                    delivery.getEnvelope().getRoutingKey());
                            properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                            consumer.receive(
                                    new org.springframework.amqp.core.Message(
                                            delivery.getBody(), properties),
                                    tag.equals("first") ? first : second);
                        } catch (Throwable failure) {
                            errors.compareAndSet(null, failure);
                        } finally {
                            done.countDown();
                        }
                    };
            first.basicQos(1);
            second.basicQos(1);
            first.basicConsume(RabbitConfiguration.QUEUE, false, "first", callback, tag -> {});
            second.basicConsume(RabbitConfiguration.QUEUE, false, "second", callback, tag -> {});
            publisher().publishOne();
            rabbitPublisher.publish(outbox.find(envelope.id(), false));
            assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isNull();
            first.basicCancel("first");
            second.basicCancel("second");
            assertThat(threads).hasSize(2);
            assertThat(effects(envelope.jobId())).isEqualTo(1);
            assertThat(status(envelope.jobId())).isEqualTo("DONE");
            int ready = channel.queueDeclarePassive(RabbitConfiguration.QUEUE).getMessageCount();
            assertThat(ready).isZero();
            java.nio.file.Path evidence =
                    java.nio.file.Path.of(
                            System.getProperty(
                                    "workhub.test.evidence", "target/evidence/phase2/regression"),
                            "real-consumers-" + envelope.id() + ".json");
            java.nio.file.Files.createDirectories(evidence.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValue(
                            evidence.toFile(),
                            java.util.Map.of(
                                    "messageId",
                                    envelope.id().toString(),
                                    "jobId",
                                    envelope.jobId().toString(),
                                    "physicalDeliveries",
                                    2,
                                    "consumerThreads",
                                    threads,
                                    "businessEffects",
                                    effects(envelope.jobId()),
                                    "jobStatus",
                                    status(envelope.jobId()),
                                    "readyMessagesAfterAcknowledgments",
                                    ready));
        }
    }

    @Autowired SourceObligations sources;

    @Test
    void endToEndCommitAndDuplicateAck() throws Exception {
        var e = seed();
        assertThat(publisher().publishOne()).isTrue();
        var delivery = get(channel);
        assertThat(delivery.getProps().getDeliveryMode()).isEqualTo(2);
        deliver(delivery, channel);
        assertThat(status(e.jobId())).isEqualTo("DONE");
        assertThat(effects(e.jobId())).isEqualTo(1);
        rabbitPublisher.publish(outbox.find(e.id(), false));
        deliver(get(channel), channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(outbox.find(e.id(), false).disposition()).isEqualTo("SUCCEEDED");
    }

    @Test
    void twoConsumersAndDifferentMessageSameJobHaveOneEffect() throws Exception {
        var e = seed();
        UUID next = UUID.randomUUID();
        outbox.insert(next, e.jobId(), 1, e.destination(), e.handler(), e.wire(), Instant.now());
        var other = outbox.find(next, false);
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await();
                                execute(e);
                                return true;
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await();
                                execute(e);
                                return true;
                            });
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        execute(other);
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(outbox.find(next, false).disposition()).isEqualTo("SUPERSEDED");
    }

    @Test
    void retryCommitLostAckOldAndEarlyDeliveryNeverDuplicateOrExecuteEarly() {
        var e = seed();
        handler.failures.set(1);
        execute(e);
        assertThat(effects(e.jobId())).isZero();
        assertThat(status(e.jobId())).isEqualTo("RETRY_WAIT");
        assertThat(outbox.find(e.id(), false).disposition()).isEqualTo("RETRY_SCHEDULED");
        assertThat(jobs.find(e.jobId(), false).attempt()).isEqualTo(1);
        execute(e);
        execute(e);
        assertThat(count("outbox_message")).isEqualTo(2);
        UUID successor = outbox.current(e.jobId(), 1);
        jdbc.update(
                "UPDATE work_job SET next_attempt_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE"
                        + " id=?",
                bytes(e.jobId()));
        execute(outbox.find(successor, false));
        assertThat(effects(e.jobId())).isZero();
        assertThat(jobs.find(e.jobId(), false).attempt()).isEqualTo(1);
        execute(e);
        execute(outbox.find(successor, false));
        assertThat(count("outbox_message")).isEqualTo(3);
        due();
        execute(outbox.find(outbox.current(e.jobId(), 2), false));
        assertThat(status(e.jobId())).isEqualTo("DONE");
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(jobs.find(e.jobId(), false).attempt()).isEqualTo(2);
    }

    @Test
    void retriesExhaustWithDurableFailureAndParkingObligation() {
        var e = seed();
        handler.failures.set(10);
        for (int n = 0; n < 5; n++) {
            due();
            execute(outbox.find(outbox.current(e.jobId(), n), false));
        }
        assertThat(status(e.jobId())).isEqualTo("FAILED");
        assertThat(jobs.find(e.jobId(), false).attempt()).isEqualTo(5);
        assertThat(effects(e.jobId())).isZero();
        assertThat(count("failure_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT category FROM failure_record", String.class))
                .isEqualTo("RETRY_EXHAUSTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_message WHERE"
                                        + " destination='work.failed'",
                                Long.class))
                .isEqualTo(1);
        execute(e);
        assertThat(count("failure_record")).isEqualTo(1);
    }

    @Test
    void unknownHandlerAndMalformedAndUnknownJobAreAuditedWithoutPoisoningValidWork() {
        var unknown = seed("UNREGISTERED");
        execute(unknown);
        assertThat(status(unknown.jobId())).isEqualTo("FAILED");
        var valid = seed();
        executor.handle(valid.id().toString(), valid.destination(), "{".getBytes());
        executor.handle(valid.id().toString(), valid.destination(), "{".getBytes());
        executor.handle(
                UUID.randomUUID().toString(),
                valid.destination(),
                ("{\"jobId\":\"" + UUID.randomUUID() + "\"}").getBytes());
        assertThat(count("failure_record")).isEqualTo(3);
        assertThat(status(valid.jobId())).isEqualTo("WAITING");
        execute(valid);
        assertThat(effects(valid.jobId())).isEqualTo(1);
    }

    @Test
    void permanentFailureRollsBackEffectAndDoesNotRetry() {
        var e = seed();
        handler.permanent = true;
        execute(e);
        assertThat(status(e.jobId())).isEqualTo("FAILED");
        assertThat(effects(e.jobId())).isZero();
        execute(e);
        assertThat(count("failure_record")).isEqualTo(1);
    }

    @Test
    void recoveryEpochIsIdempotentRespectsRetryTimeAndNeverReopensDone() {
        var e = seed();
        handler.failures.set(1);
        execute(e);
        Instant due = jobs.find(e.jobId(), false).nextAttempt();
        UUID epoch = UUID.randomUUID();
        recovery.recoverOne(epoch, e.jobId());
        recovery.recoverOne(epoch, e.jobId());
        assertThat(jobs.find(e.jobId(), false).sequence()).isEqualTo(2);
        assertThat(jobs.find(e.jobId(), false).nextAttempt()).isEqualTo(due);
        assertThat(count("transport_recovery")).isEqualTo(1);
        due();
        execute(outbox.find(outbox.current(e.jobId(), 2), false));
        recovery.recoverOne(UUID.randomUUID(), e.jobId());
        assertThat(jobs.find(e.jobId(), false).sequence()).isEqualTo(2);
    }

    @Test
    void sourceRegistrationRollbackAndMinimalHandler() {
        UUID user = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                        + " (?,?,'phase2','unused')",
                bytes(user),
                user.toString());
        jdbc.update(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,'test','','TODO','TASK','MANUAL',?)",
                bytes(task),
                bytes(project),
                bytes(user));
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(
                        () ->
                                sources.changed(
                                        project,
                                        task,
                                        SourceObligations.Owner.TASK,
                                        "body",
                                        "/test"))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(
                () ->
                        tx.executeWithoutResult(
                                s -> {
                                    sources.changed(
                                            project,
                                            task,
                                            SourceObligations.Owner.TASK,
                                            "body",
                                            "/test");
                                    throw new IllegalStateException();
                                }));
        assertThat(count("work_job")).isZero();
        tx.executeWithoutResult(
                s -> sources.changed(project, task, SourceObligations.Owner.TASK, "body", "/test"));
        UUID message =
                jdbc.queryForObject("SELECT id FROM outbox_message", (r, n) -> uuid(r.getBytes(1)));
        var e = outbox.find(message, false);
        execute(e);
        assertThat(status(e.jobId())).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT result_ref FROM work_job", String.class))
                .startsWith("phase2-validation/");
    }
}
