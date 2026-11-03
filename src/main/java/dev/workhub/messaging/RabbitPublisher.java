package dev.workhub.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class RabbitPublisher {
    private final RabbitTemplate rabbit;
    private final long timeout;

    public RabbitPublisher(
            RabbitTemplate rabbit,
            @Value("${workhub.messaging.confirm-timeout-ms:5000}") long timeout) {
        this.rabbit = rabbit;
        this.timeout = timeout;
    }

    public void publish(OutboxRepository.Envelope e) throws Exception {
        var correlation = new CorrelationData(e.id() + "/" + e.claim());
        var message =
                MessageBuilder.withBody(e.wire().getBytes(StandardCharsets.UTF_8))
                        .setMessageId(e.id().toString())
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
        rabbit.send(RabbitConfiguration.EXCHANGE, e.destination(), message, correlation);
        var confirm = correlation.getFuture().get(timeout, TimeUnit.MILLISECONDS);
        // A return can accompany a positive confirm. Spring fills it before completing this future.
        if (correlation.getReturned() != null)
            throw new IllegalStateException(
                    "MANDATORY_RETURN/" + correlation.getReturned().getReplyCode());
        if (!confirm.isAck()) throw new IllegalStateException("BROKER_NACK");
    }
}
