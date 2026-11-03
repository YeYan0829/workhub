package dev.workhub.messaging;

import com.rabbitmq.client.Channel;

import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

@Component
@ConditionalOnProperty(name = "workhub.messaging.enabled", havingValue = "true")
public class WorkJobConsumer {
    private static final Logger log = LoggerFactory.getLogger(WorkJobConsumer.class);
    private final WorkJobExecutor executor;

    public WorkJobConsumer(WorkJobExecutor executor) {
        this.executor = executor;
    }

    @RabbitListener(
            queues = {RabbitConfiguration.QUEUE, "github.work", "business.work"},
            ackMode = "MANUAL",
            autoStartup = "${workhub.messaging.consumer-enabled:true}")
    public void receive(Message message, Channel channel) throws IOException {
        try {
            executor.handle(
                    message.getMessageProperties().getMessageId(),
                    message.getMessageProperties().getReceivedRoutingKey(),
                    message.getBody());
        } catch (RuntimeException e) {
            log.warn(
                    "Consumer cannot commit; closing channel after cooldown: {}",
                    e.getClass().getSimpleName());
            // DB unavailable/unclassified infrastructure fault: no ACK, no hot nack/requeue loop.
            LockSupport.parkNanos(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            channel.abort();
            return;
        }
        // Both successful work and retry/failure disposition are durable before this ACK.
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
