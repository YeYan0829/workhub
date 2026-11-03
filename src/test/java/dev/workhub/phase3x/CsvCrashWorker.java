package dev.workhub.phase3x;

import dev.workhub.WorkHubApplication;
import dev.workhub.messaging.*;

import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.UUID;

public class CsvCrashWorker {
    public static void main(String[] args) {
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + System.getenv().getOrDefault("MYSQL_PORT", "13306")
                        + "/workhub_phase3x_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC";
        var app =
                new SpringApplicationBuilder(WorkHubApplication.class)
                        .run(
                                "--server.port=0",
                                "--debug=false",
                                "--spring.datasource.url=" + url,
                                "--workhub.messaging.enabled=false",
                                "--workhub.github.enabled=false",
                                "--workhub.bootstrap.login=");
        var jobs = app.getBean(WorkJobRepository.class);
        var outbox = app.getBean(OutboxRepository.class);
        var job = jobs.find(UUID.fromString(args[0]), false);
        var e = outbox.find(outbox.current(job.id(), job.sequence()), false);
        app.getBean(WorkJobExecutor.class)
                .handle(
                        e.id().toString(),
                        e.destination(),
                        e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Runtime.getRuntime().halt(23);
    }
}
