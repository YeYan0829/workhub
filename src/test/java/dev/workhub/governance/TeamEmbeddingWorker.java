package dev.workhub.governance;

import dev.workhub.WorkHubApplication;
import dev.workhub.knowledge.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Real second process; synthetic team credentials are read from the isolated database. */
public class TeamEmbeddingWorker {
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    String worker = args[1];
    try (var app =
        new SpringApplicationBuilder(WorkHubApplication.class)
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
                "--workhub.retrieval.enabled=true",
                "--workhub.embedding.enabled=true",
                "--workhub.embedding.legacy-mode=false",
                "--workhub.github.enabled=false",
                "--workhub.agent.enabled=false",
                "--debug=false",
                "--logging.level.root=WARN",
                "--workhub.team-model.embedding-fixture-endpoint=" + args[2],
                "--workhub.team-model.master-key="
                    + Base64.getEncoder().encodeToString(new byte[32]),
                "--workhub.model-admission.enabled=true",
                "--workhub.model-admission.redis-port="
                    + System.getenv().getOrDefault("TEAM_MODEL_REDIS_PORT", "28379"),
                "--workhub.mail.key-file=target/team-model-governance/embedding/mail.key")) {
      var teams = app.getBean(TeamEmbeddings.class);
      var calls = app.getBean(TeamEmbeddingCalls.class);
      List<Map<String, Object>> inputs =
          TeamModelFixture.JSON.readValue(
              dir.resolve("input-" + worker + ".json").toFile(), List.class);
      Files.writeString(dir.resolve("ready-" + worker), "ready");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (!Files.exists(dir.resolve("go"))) {
        if (System.nanoTime() > deadline) throw new IllegalStateException("Parent gate timeout");
        Thread.sleep(10);
      }
      var pool = Executors.newFixedThreadPool(3);
      try {
        var futures = new ArrayList<Future<Map<String, Object>>>();
        for (var input : inputs)
          futures.add(
              pool.submit(
                  () -> {
                    UUID project = UUID.fromString((String) input.get("project")),
                        actor = UUID.fromString((String) input.get("actor")),
                        operation = UUID.fromString((String) input.get("operation"));
                    try {
                      calls.embed(
                          teams.ticket(project, ((Number) input.get("config")).intValue(), actor),
                          operation,
                          "query",
                          "worker synthetic query",
                          () -> {});
                      return Map.of("operation", operation.toString(), "status", "SUCCEEDED");
                    } catch (RuntimeException e) {
                      return Map.of(
                          "operation",
                          operation.toString(),
                          "status",
                          "FAILED",
                          "code",
                          e instanceof dev.workhub.common.ApiException a ? a.code : e.getMessage());
                    }
                  }));
        var results = new ArrayList<Map<String, Object>>();
        for (var future : futures) results.add(future.get(90, TimeUnit.SECONDS));
        TeamModelFixture.JSON.writeValue(
            dir.resolve("result-" + worker + ".json").toFile(), results);
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
