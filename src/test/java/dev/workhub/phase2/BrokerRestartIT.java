package dev.workhub.phase2;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.messaging.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

class BrokerRestartIT extends Phase2Support {
    @Test
    void persistentConfirmedMessageSurvivesRealBrokerRestartAndPublisherReconnects()
            throws Exception {
        var e = seed();
        publisher().publishOne();
        connection.close();
        command("stop_app");
        try {
            var waiting = seed();
            publisher().publishOne();
            assertThat(status(waiting.jobId())).isEqualTo("WAITING");
            assertThat(outbox.find(waiting.id(), false).publishAttempt()).isEqualTo(1);
        } finally {
            command("start_app");
        }
        connection = factory().newConnection("phase2-after-restart");
        channel = connection.createChannel();
        deliver(get(channel), channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
        due();
        publisher().publishOne();
        deliver(get(channel), channel);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_job WHERE status='DONE'", Long.class))
                .isEqualTo(2);
    }

    @Test
    void lostQueueRecoveredOnceByExplicitEpoch() throws Exception {
        var e = seed();
        publisher().publishOne();
        channel.queueDelete(RabbitConfiguration.QUEUE);
        topology(channel);
        UUID epoch = UUID.randomUUID();
        recovery.recoverOne(epoch, e.jobId());
        recovery.recoverOne(epoch, e.jobId());
        publisher().publishOne();
        deliver(get(channel), channel);
        assertThat(effects(e.jobId())).isEqualTo(1);
        assertThat(jobs.find(e.jobId(), false).sequence()).isEqualTo(1);
    }

    void command(String command) throws Exception {
        var directory =
                java.nio.file.Path.of(
                        System.getProperty(
                                "workhub.test.evidence", "target/evidence/phase2/regression"));
        java.nio.file.Files.createDirectories(directory);
        var p =
                new ProcessBuilder(
                                "docker",
                                "compose",
                                "-f",
                                "compose.test.yaml",
                                "exec",
                                "-T",
                                "rabbitmq",
                                "rabbitmqctl",
                                command)
                        .redirectErrorStream(true)
                        .redirectOutput(
                                directory
                                        .resolve(command + "-" + UUID.randomUUID() + ".log")
                                        .toFile())
                        .start();
        try {
            assertThat(p.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).isZero();
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }
}
