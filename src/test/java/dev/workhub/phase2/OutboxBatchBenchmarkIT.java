package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.messaging.OutboxPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "WORKHUB_BENCH_OUTBOX", matches = "true")
class OutboxBatchBenchmarkIT extends Phase2Support {
    @Test
    void onePerTickAndBoundedBatchConfirmTheSameBurst() throws Exception {
        Path directory = Path.of("target/outbox-benchmark", UUID.randomUUID().toString());
        Files.createDirectories(directory);
        var results = new ArrayList<Map<String, Object>>();
        for (int repetition = 1; repetition <= 3; repetition++) {
            for (int batchSize : List.of(1, 20)) {
                var envelopes = new ArrayList<UUID>();
                for (int item = 0; item < 32; item++) envelopes.add(seed().id());
                var publisher = new OutboxPublisher(outbox, rabbitPublisher, retry, batchSize, 200);
                long began = System.nanoTime();
                int ticks = 0;
                while (true) {
                    publisher.tick();
                    ticks++;
                    long pending =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM outbox_message WHERE"
                                        + " publish_state<>'PUBLISHED'",
                                    Long.class);
                    if (pending == 0) break;
                    assertThat(System.nanoTime() - began).isLessThan(TimeUnit.SECONDS.toNanos(30));
                    Thread.sleep(500);
                }
                double elapsedMillis = (System.nanoTime() - began) / 1_000_000d;
                int physicalMessages = 0;
                while (channel.basicGet(dev.workhub.messaging.RabbitConfiguration.QUEUE, true)
                        != null) {
                    physicalMessages++;
                }
                for (UUID id : envelopes) {
                    assertThat(outbox.find(id, false).publishAttempt()).isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT publish_state FROM outbox_message WHERE id=?",
                                            String.class,
                                            dev.workhub.common.Ids.bytes(id)))
                            .isEqualTo("PUBLISHED");
                }
                var result =
                        Map.<String, Object>of(
                                "repetition",
                                repetition,
                                "batchSize",
                                batchSize,
                                "messages",
                                32,
                                "brokerMessages",
                                physicalMessages,
                                "ticks",
                                ticks,
                                "drainMillis",
                                elapsedMillis,
                                "effectiveMessagesPerSecond",
                                32_000d / elapsedMillis,
                                "messageIds",
                                envelopes.stream().map(UUID::toString).toList());
                results.add(result);
                new ObjectMapper().writeValue(directory.resolve("results.json").toFile(), results);
                assertThat(physicalMessages).isEqualTo(32);
            }
        }
    }
}
