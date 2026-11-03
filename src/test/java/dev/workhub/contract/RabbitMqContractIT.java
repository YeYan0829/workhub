package dev.workhub.contract;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RabbitMqContractIT {
    private Connection connection;
    private Channel channel;
    private final String name="p0."+UUID.randomUUID();
    private ConnectionFactory factory() {
        var f=new ConnectionFactory();f.setHost("127.0.0.1");f.setPort(Integer.parseInt(ContractSupport.env("RABBITMQ_PORT","15672")));
        f.setUsername(ContractSupport.env("RABBITMQ_USER","workhub"));f.setPassword(ContractSupport.required("RABBITMQ_PASSWORD"));
        f.setAutomaticRecoveryEnabled(false);f.setConnectionTimeout(5000);return f;
    }
    @BeforeEach void setup() throws Exception {
        connection=factory().newConnection("workhub-phase0-contract");channel=connection.createChannel();
        channel.exchangeDeclare(name,"direct",true);channel.queueDeclare(name,true,false,false,null);channel.queueBind(name,name,"ok");
    }
    @AfterEach void cleanup() throws Exception {
        try { if(channel!=null && channel.isOpen()) { channel.queueDelete(name);channel.exchangeDelete(name);channel.close(); } }
        finally { if(connection!=null && connection.isOpen())connection.close(); }
    }
    @Test void springConfirmAndMandatoryReturnAreIndependent() throws Exception {
        var cf=new CachingConnectionFactory(factory());
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);cf.setPublisherReturns(true);
        try {
            var template=new RabbitTemplate(cf);template.setMandatory(true);
            AtomicReference<org.springframework.amqp.core.ReturnedMessage> returned=new AtomicReference<>();
            template.setReturnsCallback(returned::set);
            var routed=new CorrelationData("routed");
            template.send(name,"ok",MessageBuilder.withBody("contract".getBytes(StandardCharsets.UTF_8)).setMessageId("stable-p0-id").build(),routed);
            assertThat(routed.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();
            assertThat(routed.getReturned()).isNull();
            var unroutable=new CorrelationData("unroutable");
            template.send(name,"missing",MessageBuilder.withBody(new byte[]{1}).build(),unroutable);
            assertThat(unroutable.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();
            assertThat(unroutable.getReturned()).isNotNull();
            assertThat(unroutable.getReturned().getReplyCode()).isEqualTo(312);
            // The user callback is asynchronous; CorrelationData itself is ready at confirm.
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(returned.get()).isNotNull();
                assertThat(returned.get().getReplyCode()).isEqualTo(312);
            });
        } finally { cf.destroy(); }
    }
    @Test void unackedConnectionCloseRedeliversSameMessageId() throws Exception {
        String messageId=UUID.randomUUID().toString();
        channel.confirmSelect();
        channel.basicPublish(name,"ok",true,new AMQP.BasicProperties.Builder().messageId(messageId).deliveryMode(2).build(),new byte[]{1,2});
        channel.waitForConfirmsOrDie(5000);
        try(var consumer=factory().newConnection("workhub-phase0-consumer")) {
            var ch=consumer.createChannel();
            var first=receive(ch);
            assertThat(first.getProps().getMessageId()).isEqualTo(messageId);
            assertThat(first.getEnvelope().isRedeliver()).isFalse();
            // Deliberately close without ACK: no business handler or retry framework.
        }
        var redelivered=receive(channel);
        assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
        assertThat(redelivered.getProps().getMessageId()).isEqualTo(messageId);
        channel.basicAck(redelivered.getEnvelope().getDeliveryTag(),false);
        assertThat(channel.basicGet(name,false)).isNull();
    }
    private GetResponse receive(Channel ch) {
        AtomicReference<GetResponse> value=new AtomicReference<>();
        await().atMost(5,TimeUnit.SECONDS).until(() -> {value.set(ch.basicGet(name,false));return value.get()!=null;});
        return value.get();
    }
}
