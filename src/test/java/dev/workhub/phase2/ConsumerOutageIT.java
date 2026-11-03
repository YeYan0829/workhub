package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConsumerOutageIT extends Phase2Support {
    @Test
    void databaseUnavailableDoesNotAckAndRealChannelCloseRedelivers() throws Exception {
        var e = seed();
        publisher().publishOne();
        var delivery = get(channel);
        var unavailable =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:1/unavailable?connectTimeout=500",
                                "unused",
                                "unused"));
        // The failure is a real JDBC connection refusal, before any durable disposition.
        var validator = new EnvelopeValidator(new com.fasterxml.jackson.databind.ObjectMapper());
        var broken =
                new WorkJobExecutor(
                        jobs,
                        new OutboxRepository(unavailable),
                        null,
                        validator,
                        null,
                        retry,
                        manager);
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setMessageId(delivery.getProps().getMessageId());
        properties.setReceivedRoutingKey(e.destination());
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        new WorkJobConsumer(broken)
                .receive(
                        new org.springframework.amqp.core.Message(delivery.getBody(), properties),
                        channel);
        assertThat(channel.isOpen()).isFalse();
        assertThat(status(e.jobId())).isEqualTo("WAITING");
        channel = connection.createChannel();
        var again = get(channel);
        assertThat(again.getEnvelope().isRedeliver()).isTrue();
        deliver(again, channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
    }
}
