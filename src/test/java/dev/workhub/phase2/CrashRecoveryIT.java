package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.messaging.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

class CrashRecoveryIT extends Phase2Support {
    @ParameterizedTest
    @MethodSource("publisherCases")
    void publisherProcessExitRecoversSameMessageIdentity(String mode) throws Exception {
        var e = seed();
        crash(mode);
        assertThat(status(e.jobId())).isEqualTo("WAITING");
        due();
        publisher().publishOne();
        deliver(get(channel), channel);
        // Send-before-confirm may or may not reach the broker; drain all actual duplicates.
        com.rabbitmq.client.GetResponse extra;
        while ((extra = channel.basicGet(RabbitConfiguration.QUEUE, false)) != null)
            deliver(extra, channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(status(e.jobId())).isEqualTo("DONE");
        assertThat(outbox.find(e.id(), false).publishAttempt()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("consumerCases")
    void consumerProcessExitRedeliversWithoutDuplicateEffect(String mode) throws Exception {
        var e = seed();
        publisher().publishOne();
        crash(mode);
        assertThat(effects(e.jobId())).isEqualTo(mode.equals("after-commit") ? 1 : 0);
        var redelivery = get(channel);
        assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
        deliver(redelivery, channel);
        if (mode.equals("retry-commit")) {
            assertThat(count("outbox_message")).isEqualTo(2);
            assertThat(effects(e.jobId())).isZero();
            due();
            publisher().publishOne();
            deliver(get(channel), channel);
        }
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(status(e.jobId())).isEqualTo("DONE");
    }

    static java.util.stream.Stream<String> publisherCases() {
        return repetitions()
                .boxed()
                .flatMap(
                        n ->
                                java.util.stream.Stream.of(
                                        "claim", "publish-send", "publish-confirm"));
    }

    static java.util.stream.Stream<String> consumerCases() {
        return repetitions()
                .boxed()
                .flatMap(
                        n ->
                                java.util.stream.Stream.of(
                                        "before-commit", "after-commit", "retry-commit"));
    }

    static java.util.stream.IntStream repetitions() {
        int count = Integer.getInteger("workhub.test.crash-repetitions", 3);
        if (count < 1) throw new IllegalArgumentException("Crash repetitions must be positive");
        return java.util.stream.IntStream.range(0, count);
    }

    void crash(String mode) throws Exception {
        Path directory = Path.of(System.getProperty("workhub.test.evidence", "target/evidence/phase2/regression") + "/processes");
        Files.createDirectories(directory);
        Path output = directory.resolve(mode + "-" + java.util.UUID.randomUUID() + ".log");
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-Xmx192m",
                                "-XX:ActiveProcessorCount=2",
                                "-cp",
                                System.getProperty("java.class.path"),
                                CrashWorker.class.getName(),
                                mode)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            // This watchdog includes a complete application startup, not a business deadline.
            long childTimeout = Long.getLong("workhub.test.child-timeout-seconds", 45L);
            assertThat(process.waitFor(childTimeout, TimeUnit.SECONDS))
                    .as("child exit; log %s", output)
                    .isTrue();
            assertThat(process.exitValue())
                    .as("deliberate halt; log %s", output)
                    .isIn(71, 72, 73, 74);
            System.out.println(
                    "PHASE2_PROCESS mode="
                            + mode
                            + " exit="
                            + process.exitValue()
                            + " log="
                            + output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
