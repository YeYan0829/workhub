package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.*;
import dev.workhub.messaging.*;
import dev.workhub.work.ContentService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "workhub.messaging.enabled=false", "workhub.retrieval.enabled=true",
          "workhub.embedding.enabled=true",
      "workhub.embedding.legacy-mode=false", "workhub.agent.legacy-mode=false",
          "workhub.agent.enabled=true",
      "workhub.github.enabled=false", "workhub.bootstrap.login=",
          "workhub.model-admission.enabled=true",
      "server.servlet.session.cookie.secure=false", "debug=false", "logging.level.root=WARN",
      "workhub.mail.key-file=target/model-settings-ui/mail.key"
    })
class ModelSettingsUIIT {
  static final MockWebServer CHAT = new MockWebServer(), MODEL = new MockWebServer();

  static {
    try {
      CHAT.start();
      MODEL.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            System.getenv()
                .getOrDefault(
                    "TEAM_MODEL_JDBC_URL",
                    "jdbc:mysql://127.0.0.1:28306/workhub_team_model_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
    p.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("TEAM_MODEL_DB_USER", "team_model_fixture"));
    p.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("TEAM_MODEL_DB_PASSWORD", "team-model-local-only"));
    p.add(
        "workhub.model-admission.redis-port",
        () -> System.getenv().getOrDefault("TEAM_MODEL_REDIS_PORT", "28379"));
    p.add("workhub.team-model.fixture-endpoint", () -> CHAT.url("/chat").toString());
    p.add(
        "workhub.team-model.embedding-fixture-endpoint", () -> MODEL.url("/embedding").toString());
    p.add("workhub.team-model.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    p.add(
        "workhub.retrieval.es-url",
        () -> System.getenv().getOrDefault("TEAM_MODEL_ES_URL", "http://127.0.0.1:29200"));
    p.add("workhub.retrieval.index", () -> "model-ui-text-" + INDEX);
  }

  static final UUID INDEX = UUID.randomUUID();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired ObjectMapper mapper;
  @Autowired PasswordEncoder encoder;
  @Autowired TeamModels models;
  @Autowired TeamEmbeddings embeddings;
  @Autowired ContentService content;
  @Autowired OutboxRepository outbox;
  @Autowired WorkJobExecutor executor;
  @Autowired AgentRuns runs;
  TeamModelFixture f;
  TeamModelFixture.Team team, other;
  ActorContext maintainer;
  AtomicInteger embeddingCalls = new AtomicInteger();
  Set<String> initialized = ConcurrentHashMap.newKeySet();

  @Test
  void teamAndProjectSettingsCompleteBrowserJourney() throws Exception {
    CHAT.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest request) {
            try {
              var body = mapper.readTree(request.getBody().readUtf8());
              String ref = null;
              for (var message : body.path("messages"))
                if (message.path("role").asText().equals("tool"))
                  for (var citation :
                      mapper.readTree(message.path("content").asText()).path("citations"))
                    ref = citation.path("id").asText();
              Object reply;
              String finish;
              if (ref == null) {
                finish = "tool_calls";
                reply =
                    Map.of(
                        "role",
                        "assistant",
                        "content",
                        "",
                        "tool_calls",
                        List.of(
                            Map.of(
                                "id",
                                "embedding_search",
                                "type",
                                "function",
                                "function",
                                Map.of(
                                    "name",
                                    "search_project_evidence",
                                    "arguments",
                                    json.write(
                                        Map.of("query", "交接", "strategy", "DENSE", "limit", 5))))));
              } else {
                finish = "stop";
                reply =
                    Map.of(
                        "role",
                        "assistant",
                        "content",
                        json.write(
                            Map.of(
                                "paragraphs",
                                List.of(Map.of("text", "已查阅项目资料。", "citations", List.of(ref))))));
              }
              return new MockResponse()
                  .setHeader("Content-Type", "application/json")
                  .setBody(
                      json.write(
                          Map.of(
                              "id",
                              "team-embedding-chat",
                              "model",
                              body.path("model").asText(),
                              "choices",
                              List.of(
                                  Map.of("index", 0, "message", reply, "finish_reason", finish)),
                              "usage",
                              Map.of(
                                  "prompt_tokens",
                                  100,
                                  "completion_tokens",
                                  60,
                                  "total_tokens",
                                  160))));
            } catch (Exception e) {
              return new MockResponse().setResponseCode(500);
            }
          }
        });

    MODEL.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest request) {
            try {
              int dims =
                  mapper.readTree(request.getBody().readUtf8()).at("/parameters/dimension").asInt();
              List<Integer> vector = new ArrayList<>();
              for (int i = 0; i < dims; i++) vector.add(i == 0 ? 1 : 0);
              Map<String, Object> body = new LinkedHashMap<>();
              body.put(
                  "output",
                  Map.of("embeddings", List.of(Map.of("text_index", 0, "embedding", vector))));
              if (embeddingCalls.incrementAndGet() % 2 == 1)
                body.put("usage", Map.of("total_tokens", 10));
              return new MockResponse()
                  .setHeader("Content-Type", "application/json")
                  .setBody(json.write(body))
                  .setBodyDelay(150, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
              return new MockResponse().setResponseCode(500);
            }
          }
        });
    f = new TeamModelFixture("http://127.0.0.1:1/unused", Clock.systemUTC());
    team = f.team();
    other = f.team();
    maintainer = f.user();
    String password = "Model-UI-Local-2026!";
    for (var a : List.of(team.admin(), team.member(), other.admin(), maintainer))
      db.update(
          "UPDATE app_user SET password_hash=?,display_name=? WHERE id=?",
          encoder.encode(password),
          "演示成员",
          bytes(a.userId()));
    db.update(
        "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'MEMBER')",
        bytes(team.workspace()),
        bytes(maintainer.userId()));
    db.update(
        "INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MAINTAINER')",
        bytes(team.first()),
        bytes(maintainer.userId()));
    db.update("UPDATE workspace SET name='WorkHub 模型服务演示' WHERE id=?", bytes(team.workspace()));
    db.update("UPDATE project SET name='试运行交接' WHERE id=?", bytes(team.first()));
    content.createDocument(
        team.admin(),
        team.first(),
        dev.workhub.work.ContentRepository.DocumentKind.DOCUMENT,
        "试运行交接约定",
        "交接前确认外部问题处理约定，并核对负责人与任务进展。",
        UUID.randomUUID().toString());
    assertThat(runs.availability(team.admin(), team.first()))
        .containsEntry("reason", "MODEL_NOT_CONFIGURED");
    assertThatThrownBy(() -> models.catalog(team.member(), team.workspace()))
        .hasFieldOrPropertyWithValue("code", "FORBIDDEN");
    assertThatThrownBy(() -> embeddings.catalog(other.admin(), team.workspace()))
        .hasFieldOrPropertyWithValue("code", "FORBIDDEN");
    Path output = Path.of("target/model-settings-ui", UUID.randomUUID().toString());
    Files.createDirectories(output);
    Map<String, Object> seed = new LinkedHashMap<>();
    seed.put("base", "http://127.0.0.1:" + port);
    seed.put("workspaceId", team.workspace());
    seed.put("projectId", team.first());
    seed.put("otherWorkspaceId", other.workspace());
    seed.put("password", password);
    seed.put("admin", "team-" + team.admin().userId());
    seed.put("member", "team-" + team.member().userId());
    seed.put("maintainer", "team-" + maintainer.userId());
    seed.put("otherAdmin", "team-" + other.admin().userId());
    Path seedFile = output.resolve("seed.json");
    Files.writeString(seedFile, json.write(seed));
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicReference<Throwable> workerError = new AtomicReference<>();
    var worker = Executors.newSingleThreadExecutor();
    var background =
        worker.submit(
            () -> {
              while (running.get()) {
                try {
                  initialize();
                  var rows =
                      db.queryForList(
                          "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE"
                              + " j.project_id=? AND j.handler IN"
                              + " ('INDEX_TEXT','PUBLISH_TEXT','TEAM_EMBED_REBUILD','TEAM_EMBED_TEXT','TEAM_EMBED_CHUNK','TEAM_EMBED_PUBLISH','RUN_AGENT')"
                              + " AND o.consume_disposition='UNHANDLED' AND j.status NOT IN"
                              + " ('DONE','FAILED') AND j.next_attempt_at<=UTC_TIMESTAMP(6) AND"
                              + " o.available_at<=UTC_TIMESTAMP(6) ORDER BY j.created_at,j.id LIMIT"
                              + " 10",
                          bytes(team.first()));
                  for (var row : rows) {
                    var e = outbox.find(uuid((byte[]) row.get("id")), false);
                    executor.handle(
                        e.id().toString(),
                        e.destination(),
                        e.wire().getBytes(StandardCharsets.UTF_8));
                  }
                  Thread.sleep(50);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                } catch (Throwable e) {
                  workerError.compareAndSet(null, e);
                  return;
                }
              }
            });
    Process browser = null;
    try {
      ProcessBuilder command = new ProcessBuilder("node", "scripts/verify-model-settings-ui.cjs");
      command.environment().put("WORKHUB_MODEL_UI_TEST", "true");
      command.environment().put("MODEL_UI_SEED_FILE", seedFile.toAbsolutePath().toString());
      command.environment().put("MODEL_UI_TEST_DIR", output.toAbsolutePath().toString());
      command.redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
      browser = command.start();
      assertThat(browser.waitFor(240, TimeUnit.SECONDS))
          .as("browser timeout; see %s", output)
          .isTrue();
      assertThat(workerError.get()).isNull();
      assertThat(browser.exitValue())
          .as("%s", Files.readString(output.resolve("browser.log")))
          .isZero();
      assertThat(CHAT.getRequestCount()).isGreaterThan(0);
      assertThat(MODEL.getRequestCount()).isGreaterThan(0);
      System.out.println("MODEL_UI_EVIDENCE " + output.toAbsolutePath());
    } finally {
      if (browser != null && browser.isAlive()) browser.destroyForcibly();
      running.set(false);
      worker.shutdownNow();
      worker.awaitTermination(15, TimeUnit.SECONDS);
      f.close();
    }
  }

  void initialize() {
    try {
      var c = models.getConnection(team.admin(), team.workspace());
      initialize(
          new ModelAdmission.Quota(c.quotaScope(), c.version(), c.requestsPerSecond(), c.burst()));
    } catch (ApiException e) {
      if (!e.code.equals("MODEL_NOT_CONFIGURED")) throw e;
    }
    try {
      var c = embeddings.getConnection(team.admin(), team.workspace());
      if (c.quota() != null) initialize(c.quota());
    } catch (ApiException e) {
      if (!e.code.equals("EMBEDDING_NOT_CONFIGURED")) throw e;
    }
  }

  void initialize(ModelAdmission.Quota q) {
    if (!initialized.add(q.scope())) return;
    var script = new org.springframework.data.redis.core.script.DefaultRedisScript<List>();
    script.setLocation(new org.springframework.core.io.ClassPathResource("model/admission.lua"));
    script.setResultType(List.class);
    String key = "workhub:model:{" + q.scope() + "}";
    f.redis()
        .execute(
            script,
            List.of(key, key + ":init"),
            "initialize",
            "team-v1|" + q.version() + "|" + q.rate() + "|" + q.burst(),
            "" + q.rate(),
            "" + q.burst(),
            "" + (long) Math.ceil(1000 * q.burst() / q.rate()),
            UUID.randomUUID().toString());
  }

  @AfterAll
  static void stop() throws Exception {
    CHAT.close();
    MODEL.close();
  }
}
