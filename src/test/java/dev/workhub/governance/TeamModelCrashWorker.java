package dev.workhub.governance;

import dev.workhub.WorkHubApplication;
import dev.workhub.agent.RunCheckpointStore;
import dev.workhub.common.Ids;
import dev.workhub.messaging.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Process fault after the first committed team model/tool boundary. No production fault switches.
 */
public class TeamModelCrashWorker {
  @Configuration
  @org.springframework.context.annotation.Profile("team-model-crash-worker")
  static class Fault {
    @EventListener
    public void boundary(RunCheckpointStore.Boundary b) {
      if (b.calls() == 1 && b.phase().equals("AFTER")) Runtime.getRuntime().halt(86);
    }
  }

  public static void main(String[] args) {
    try (var app =
        new SpringApplicationBuilder(WorkHubApplication.class, Fault.class)
            .profiles("team-model-crash-worker")
            .run(
                "--spring.datasource.url="
                    + System.getenv()
                        .getOrDefault(
                            "TEAM_MODEL_JDBC_URL",
                            "jdbc:mysql://127.0.0.1:28306/workhub_team_model_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"),
                "--spring.datasource.username="
                    + System.getenv().getOrDefault("TEAM_MODEL_DB_USER", "team_model_fixture"),
                "--spring.datasource.password="
                    + System.getenv()
                        .getOrDefault("TEAM_MODEL_DB_PASSWORD", "team-model-local-only"),
                "--server.port=0",
                "--workhub.bootstrap.login=",
                "--workhub.messaging.enabled=false",
                "--workhub.retrieval.enabled=false",
                "--workhub.embedding.enabled=false",
                "--workhub.github.enabled=false",
                "--workhub.agent.enabled=true",
                "--workhub.agent.legacy-mode=false",
                "--workhub.team-model.fixture-endpoint=" + args[1],
                "--workhub.team-model.master-key="
                    + Base64.getEncoder().encodeToString(new byte[32]),
                "--workhub.model-admission.enabled=true",
                "--workhub.model-admission.redis-port="
                    + System.getenv().getOrDefault("TEAM_MODEL_REDIS_PORT", "28379"),
                "--workhub.mail.key-file=target/team-model-governance/mail.key",
                "--debug=false",
                "--logging.level.root=WARN",
                "--logging.level.org.springframework.jdbc.core=WARN")) {
      var db = app.getBean(JdbcTemplate.class);
      var outbox = app.getBean(OutboxRepository.class);
      var executor = app.getBean(WorkJobExecutor.class);
      UUID id =
          db.queryForObject(
              "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE"
                  + " j.owner_ref=? ORDER BY o.created_at DESC LIMIT 1",
              (r, n) -> Ids.read(r, "id"),
              Ids.bytes(UUID.fromString(args[0])));
      var e = outbox.find(id, false);
      executor.handle(
          e.id().toString(), e.destination(), e.wire().getBytes(StandardCharsets.UTF_8));
    }
  }
}
