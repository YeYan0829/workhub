package dev.workhub.agent;
import com.rabbitmq.client.Channel;
import dev.workhub.messaging.WorkJobConsumer;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name={"workhub.agent.enabled","workhub.messaging.enabled"},havingValue="true")
public class AgentConsumer {
  private final WorkJobConsumer consumer;
  public AgentConsumer(WorkJobConsumer consumer){this.consumer=consumer;}
  @RabbitListener(queues="agent.read",ackMode="MANUAL",autoStartup="${workhub.messaging.consumer-enabled:true}")
  public void receive(Message message,Channel channel)throws IOException{consumer.receive(message,channel);}
}
