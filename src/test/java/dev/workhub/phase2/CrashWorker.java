package dev.workhub.phase2;

import dev.workhub.WorkHubApplication;
import dev.workhub.messaging.*;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only process: halt bypasses finally/shutdown hooks and leaves DB/MQ to recover. */
public class CrashWorker {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        try (var app =
                new SpringApplicationBuilder(WorkHubApplication.class, Phase2Support.Handlers.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.port=0",
                                "--spring.datasource.url=" + Phase2Support.url(),
                                "--spring.rabbitmq.virtual-host=phase2_test",
                                "--workhub.messaging.enabled=false",
                                "--workhub.bootstrap.login=",
                                "--debug=false")) {
            var outbox = app.getBean(OutboxRepository.class);
            var publisher = app.getBean(RabbitPublisher.class);
            if (mode.equals("claim")) {
                outbox.claim(30);
                Runtime.getRuntime().halt(71);
            }
            if (mode.equals("publish-confirm")) {
                var e = outbox.claim(30);
                publisher.publish(e);
                Runtime.getRuntime().halt(72);
            }
            if (mode.equals("publish-send")) {
                var e = outbox.claim(30);
                try (var c = Phase2Support.factory().newConnection("phase2-send-crash")) {
                    var ch = c.createChannel();
                    ch.confirmSelect();
                    ch.basicPublish(
                            RabbitConfiguration.EXCHANGE,
                            e.destination(),
                            true,
                            new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                                    .messageId(e.id().toString())
                                    .deliveryMode(2)
                                    .build(),
                            e.wire().getBytes(StandardCharsets.UTF_8));
                    Runtime.getRuntime().halt(72);
                }
            }
            try (var c = Phase2Support.factory().newConnection("phase2-consumer-crash")) {
                var ch = c.createChannel();
                var delivery = ch.basicGet(RabbitConfiguration.QUEUE, false);
                if (delivery == null) throw new IllegalStateException("No delivery");
                var handler = app.getBean(Phase2Support.LocalHandler.class);
                handler.crash = mode.equals("before-commit");
                if (mode.equals("retry-commit")) handler.failures.set(1);
                app.getBean(WorkJobExecutor.class)
                        .handle(
                                delivery.getProps().getMessageId(),
                                delivery.getEnvelope().getRoutingKey(),
                                delivery.getBody());
                Runtime.getRuntime().halt(74); // DB committed; deliberately no ACK.
            }
        }
    }
}
