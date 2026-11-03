package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.common.Inputs;
import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class ProtocolTest {
    @Test
    void boundedRetryAndJitter() {
        var policy = new RetryPolicy();
        assertThat(policy.ceilingMillis(1)).isEqualTo(1000);
        assertThat(policy.ceilingMillis(100)).isEqualTo(60000);
        assertThat(policy.exhausted(4)).isFalse();
        assertThat(policy.exhausted(5)).isTrue();
        for (int n = 1; n < 100; n++)
            assertThat(policy.delayMillis(n)).isBetween(0L, policy.ceilingMillis(n));
    }

    @Test
    void registryRejectsDuplicatesAndUnknownHandler() {
        var handler =
                new WorkHandler() {
                    public String kind() {
                        return "x";
                    }

                    public Result execute(WorkJobRepository.Job j) {
                        return new Result("ok", Inputs.hash("ok"));
                    }
                };
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler, handler)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler)).require("missing"))
                .isInstanceOf(WorkFailure.class);
    }

    @Test
    void envelopeRequiresExactDurableBodyScopeVersionAndHash() throws Exception {
        var mapper = new ObjectMapper();
        var validator = new EnvelopeValidator(mapper);
        UUID job = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
        String hash = Inputs.hash("{}");
        String wire =
                mapper.writeValueAsString(
                        Map.of(
                                "jobId",
                                job,
                                "projectId",
                                project,
                                "kind",
                                "TEST",
                                "schemaVersion",
                                1,
                                "payloadHash",
                                hash));
        var j =
                new WorkJobRepository.Job(
                        job,
                        project,
                        job,
                        "TEST",
                        "TEST",
                        "{}",
                        hash,
                        "WAITING",
                        0,
                        0,
                        Instant.now());
        var e =
                new OutboxRepository.Envelope(
                        id,
                        job,
                        0,
                        "knowledge.index",
                        "TEST",
                        1,
                        wire,
                        Inputs.hash(wire),
                        "UNHANDLED",
                        null,
                        0,
                        Instant.now());
        assertThat(validator.matches(wire.getBytes(), "knowledge.index", e, j)).isTrue();
        assertThat(validator.matches((wire + " ").getBytes(), "knowledge.index", e, j)).isFalse();
        assertThat(validator.matches(wire.getBytes(), "work.failed", e, j)).isFalse();
        assertThat(validator.identity("1-1-1-1-1")).isNull();
    }
}
