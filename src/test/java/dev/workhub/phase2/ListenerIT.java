package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

@SpringBootTest(
        properties = {
            "workhub.messaging.enabled=true",
            "workhub.messaging.poll-ms=100",
            "workhub.messaging.consumer-enabled=false",
            "workhub.bootstrap.login=",
            "debug=false"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListenerIT extends Phase2Support {
    @Autowired RabbitListenerEndpointRegistry listeners;

    @Test
    void scheduledPublisherAndRealListenerRecoverAfterConsumerRestart() {
        listeners.stop();
        var e = seed();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT publish_state FROM outbox_message"
                                                            + " WHERE id=?",
                                                        String.class,
                                                        dev.workhub.common.Ids.bytes(e.id())))
                                        .isEqualTo("PUBLISHED"));
        assertThat(status(e.jobId())).isEqualTo("WAITING");
        listeners.start();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(e.jobId())).isEqualTo("DONE"));
        assertThat(effects(e.jobId())).isEqualTo(1);
    }
}
