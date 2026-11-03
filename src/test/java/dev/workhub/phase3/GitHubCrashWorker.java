package dev.workhub.phase3;

import dev.workhub.WorkHubApplication;
import dev.workhub.messaging.*;

import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Parent kills this JVM only after the HTTP fake has durably observed the PATCH. */
public class GitHubCrashWorker {
    public static void main(String[] args) {
        try (var app =
                new SpringApplicationBuilder(WorkHubApplication.class)
                        .run(
                                "--server.port=0",
                                "--debug=false",
                                "--spring.datasource.url=" + args[0],
                                "--workhub.github.api-base=" + args[1],
                                "--workhub.github.fake-token=fake",
                                "--workhub.github.dispatch-enabled=true",
                                "--workhub.messaging.enabled=false",
                                "--workhub.bootstrap.login=")) {
            var jobs = app.getBean(WorkJobRepository.class);
            var outbox = app.getBean(OutboxRepository.class);
            var j = jobs.find(UUID.fromString(args[2]), false);
            var e = outbox.find(outbox.current(j.id(), j.sequence()), false);
            app.getBean(WorkJobExecutor.class)
                    .handle(
                            e.id().toString(),
                            e.destination(),
                            e.wire().getBytes(StandardCharsets.UTF_8));
        }
    }
}
