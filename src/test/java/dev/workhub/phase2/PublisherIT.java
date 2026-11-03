package dev.workhub.phase2;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.concurrent.*;

class PublisherIT extends Phase2Support {
    @Test
    void concurrentClaimSkipLockedAndExpiredClaimFencing() throws Exception {
        var e = seed();
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return outbox.claim(30);
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return outbox.claim(30);
                            });
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat((first == null) ^ (second == null)).isTrue();
            var stale = first == null ? second : first;
            due();
            var current = outbox.claim(30);
            assertThat(current.claim()).isNotEqualTo(stale.claim());
            assertThat(outbox.confirmed(stale)).isFalse();
            outbox.publishFailed(stale, "stale", 1000);
            assertThat(outbox.find(e.id(), false).claim()).isEqualTo(current.claim());
            rabbitPublisher.publish(current);
            assertThat(outbox.confirmed(current)).isTrue();
            deliver(get(channel), channel);
            assertThat(effects(e.jobId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unavailableBrokerPreservesObligationThenRecovers() throws Exception {
        var e = seed();
        var cf = new CachingConnectionFactory("127.0.0.1", 1);
        cf.setConnectionTimeout(500);
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);
        try {
            var template = new RabbitTemplate(cf);
            template.setMandatory(true);
            new OutboxPublisher(outbox, new RabbitPublisher(template, 500), retry).publishOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT publish_state FROM outbox_message WHERE id=?",
                                    String.class,
                                    bytes(e.id())))
                    .isEqualTo("PENDING");
            assertThat(outbox.find(e.id(), false).publishAttempt()).isEqualTo(1);
            assertThat(status(e.jobId())).isEqualTo("WAITING");
        } finally {
            cf.destroy();
        }
        due();
        publisher().publishOne();
        deliver(get(channel), channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
    }

    @Test
    void mandatoryReturnCannotBeMisclassifiedAsPublished() throws Exception {
        var e = seed();
        channel.queueUnbind(
                RabbitConfiguration.QUEUE, RabbitConfiguration.EXCHANGE, RabbitConfiguration.QUEUE);
        try {
            publisher().publishOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT publish_state FROM outbox_message WHERE id=?",
                                    String.class,
                                    bytes(e.id())))
                    .isEqualTo("PENDING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_publish_error FROM outbox_message WHERE id=?",
                                    String.class,
                                    bytes(e.id())))
                    .startsWith("MANDATORY_RETURN");
        } finally {
            channel.queueBind(
                    RabbitConfiguration.QUEUE,
                    RabbitConfiguration.EXCHANGE,
                    RabbitConfiguration.QUEUE);
        }
        due();
        publisher().publishOne();
        deliver(get(channel), channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
    }

    @Test
    void parkingUnavailableKeepsAuditAndPendingSummary() throws Exception {
        var e = seed("UNKNOWN");
        execute(e);
        // Publish original terminal envelope too: consumer disposition safely skips it.
        publisher().publishOne();
        channel.queueUnbind(
                RabbitConfiguration.PARKING,
                RabbitConfiguration.EXCHANGE,
                RabbitConfiguration.PARKING);
        try {
            publisher().publishOne();
            assertThat(count("failure_record")).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT publish_state FROM outbox_message WHERE"
                                        + " destination='work.failed'",
                                    String.class))
                    .isEqualTo("PENDING");
        } finally {
            channel.queueBind(
                    RabbitConfiguration.PARKING,
                    RabbitConfiguration.EXCHANGE,
                    RabbitConfiguration.PARKING);
        }
        due();
        publisher().publishOne();
        assertThat(channel.basicGet(RabbitConfiguration.PARKING, true)).isNotNull();
    }

    @Test
    void consumerCanCommitBeforePublisherMarksConfirmed() throws Exception {
        var e = seed();
        var claim = outbox.claim(30);
        rabbitPublisher.publish(claim);
        deliver(get(channel), channel);
        assertThat(status(e.jobId())).isEqualTo("DONE");
        assertThat(outbox.confirmed(claim)).isTrue();
        assertThat(outbox.find(e.id(), false).disposition()).isEqualTo("SUCCEEDED");
    }
}
