package dev.workhub.knowledge;

import com.rabbitmq.client.Channel;
import dev.workhub.messaging.WorkJobConsumer;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Separate prefetch-one container keeps model IO off the index/GitHub consumer. */
@Component
@ConditionalOnProperty(
    name = {"workhub.embedding.enabled", "workhub.messaging.enabled"},
    havingValue = "true")
public class EmbeddingConsumer {
  private final WorkJobConsumer consumer;

  public EmbeddingConsumer(WorkJobConsumer consumer) {
    this.consumer = consumer;
  }

  @RabbitListener(
      queues = "knowledge.embedding",
      ackMode = "MANUAL",
      autoStartup = "${workhub.messaging.consumer-enabled:true}")
  public void receive(Message message, Channel channel) throws IOException {
    consumer.receive(message, channel);
  }
}
