package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "debug=false",
      "workhub.messaging.enabled=false",
      "workhub.retrieval.enabled=false",
      "workhub.embedding.enabled=false",
      "workhub.github.enabled=false",
      "workhub.bootstrap.login=",
      "workhub.agent.enabled=true",
      "workhub.agent.legacy-mode=false",
      "workhub.model-admission.enabled=true",
      "logging.level.root=WARN",
      "logging.level.org.springframework.jdbc.core=WARN",
      "workhub.mail.key-file=target/team-model-governance/mail.key"
    })
@AutoConfigureMockMvc
class TeamModelRuntimeIT {
  static final MockWebServer MODEL = new MockWebServer();

  static {
    try {
      MODEL.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    p.add("workhub.team-model.fixture-endpoint", () -> MODEL.url("/chat/completions").toString());
    p.add("workhub.team-model.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    p.add(
        "workhub.model-admission.redis-port",
        () -> System.getenv().getOrDefault("TEAM_MODEL_REDIS_PORT", "28379"));
  }

  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired ObjectMapper mapper;
  @Autowired TeamModels teams;
  @Autowired AgentRuns runs;
  @Autowired ConversationService conversations;
  @Autowired TaskService tasks;
  @Autowired AutomaticTriage automatic;
  @Autowired WorkJobExecutor executor;
  @Autowired OutboxRepository outbox;
  @Autowired ContextPolicy policy;
  @Autowired MockMvc mvc;
  TeamModelFixture f;
  TeamModelFixture.Team team;
  UUID task;
  AtomicInteger summaries = new AtomicInteger();

  @BeforeEach
  void setup() {
    f = new TeamModelFixture(MODEL.url("/chat/completions").toString(), Clock.systemUTC());
    team = f.team();
    f.configure(team, 1000, 1);
    task =
        tasks
            .create(
                team.admin(),
                team.first(),
                new TaskRequests.Create(
                    "Team task",
                    "A bounded source",
                    TaskStatus.TODO,
                    TaskType.TASK,
                    1,
                    team.admin().userId(),
                    null),
                UUID.randomUUID().toString())
            .id();
    policy.triggerBytes = 60000;
    policy.recentTurns = 2;
    MODEL.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            try {
              var q = mapper.readTree(r.getBody().readUtf8());
              Object message;
              String finish = "stop";
              String wire = q.toString();
              if (wire.contains("整理较早会话")) {
                summaries.incrementAndGet();
                message =
                    Map.of(
                        "role",
                        "assistant",
                        "content",
                        json.write(
                            Map.of(
                                "goals",
                                List.of("查阅任务"),
                                "constraints",
                                List.of(),
                                "corrections",
                                List.of(),
                                "objects",
                                List.of(),
                                "unresolved",
                                List.of(),
                                "observations",
                                List.of(),
                                "readback",
                                List.of())));
              } else {
                String ref = null;
                boolean triage = wire.contains("单任务分诊");
                for (var m : q.path("messages"))
                  if (m.path("role").asText().equals("tool"))
                    for (var c : mapper.readTree(m.path("content").asText()).path("citations"))
                      if (c.path("kind").asText().equals("FACT")) ref = c.path("id").asText();
                if (triage) {
                  message =
                      Map.of(
                          "role",
                          "assistant",
                          "content",
                          json.write(
                              Map.of(
                                  "paragraphs",
                                  List.of(Map.of("text", "需要进一步确认", "citations", List.of())),
                                  "suggestion",
                                  Map.of("type", "TASK", "priority", 1),
                                  "investigation",
                                  Map.of(
                                      "recommendation",
                                      "NEED_INFO",
                                      "relatedTasks",
                                      List.of(),
                                      "questions",
                                      List.of("请补充验收条件")))));
                } else if (ref == null) {
                  finish = "tool_calls";
                  message =
                      Map.of(
                          "role",
                          "assistant",
                          "content",
                          "",
                          "tool_calls",
                          List.of(
                              Map.of(
                                  "id",
                                  "team_tool",
                                  "type",
                                  "function",
                                  "function",
                                  Map.of(
                                      "name",
                                      "get_task",
                                      "arguments",
                                      json.write(Map.of("taskId", task))))));
                } else
                  message =
                      Map.of(
                          "role",
                          "assistant",
                          "content",
                          json.write(
                              Map.of(
                                  "paragraphs",
                                  List.of(
                                      Map.of(
                                          "text",
                                          "当前任务为 TODO。" + "测试资料".repeat(400),
                                          "citations",
                                          List.of(ref))))));
              }
              return new MockResponse()
                  .setHeader("Content-Type", "application/json")
                  .setBody(
                      json.write(
                          Map.of(
                              "id",
                              "team-runtime",
                              "model",
                              "glm-4.7",
                              "choices",
                              List.of(
                                  Map.of("index", 0, "message", message, "finish_reason", finish)),
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
  }

  @AfterEach
  void close() {
    f.close();
  }

  @AfterAll
  static void stop() throws Exception {
    MODEL.close();
  }

  void pump() {
    for (int i = 0; i < 30; i++) {
      var rows =
          db.queryForList(
              "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE"
                  + " j.project_id=? AND j.handler='RUN_AGENT' AND"
                  + " o.consume_disposition='UNHANDLED' AND j.status NOT IN ('DONE','FAILED') ORDER"
                  + " BY o.created_at,o.id",
              bytes(team.first()));
      if (rows.isEmpty()) return;
      for (var r : rows) {
        var e = outbox.find(uuid((byte[]) r.get("id")), false);
        db.update(
            "UPDATE outbox_message SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
            bytes(e.id()));
        db.update(
            "UPDATE work_job SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
            bytes(e.jobId()));
        e = outbox.find(e.id(), false);
        executor.handle(
            e.id().toString(), e.destination(), e.wire().getBytes(StandardCharsets.UTF_8));
      }
    }
    throw new AssertionError("jobs did not drain");
  }

  @Test
  void manualQuestionConversationCompressionAndAutomaticAnalysisUseTeamLedger() {
    var manual =
        runs.create(
            team.admin(),
            team.first(),
            new AgentRuns.Request("任务是什么状态"),
            UUID.randomUUID().toString());
    pump();
    assertThat(runs.get(team.admin(), team.first(), manual.id()).status()).isEqualTo("SUCCEEDED");
    UUID c = conversations.create(team.admin(), team.first()).id();
    for (int i = 0; i < 3; i++) {
      var turn =
          conversations.submit(
              team.admin(),
              c,
              new ConversationService.Submit(
                  "请查询任务背景。" + "背景资料".repeat(350),
                  conversations.get(team.admin(), c).historyRevision()),
              UUID.randomUUID().toString(),
              null);
      pump();
      assertThat(runs.get(team.admin(), team.first(), turn.runId()).status())
          .as(json.write(runs.get(team.admin(), team.first(), turn.runId())))
          .isEqualTo("SUCCEEDED");
      policy.triggerBytes = 4000;
      policy.recentTurns = 1;
    }
    assertThat(summaries.get()).isPositive();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_model_usage WHERE run_id IN (SELECT id FROM agent_run"
                    + " WHERE conversation_id=?) AND purpose='COMPACTION'",
                Long.class,
                bytes(c)))
        .isPositive();
    automatic.update(
        team.admin(), team.first(), new AutomaticTriage.Update(true, TriageStrategy.WORKFLOW, 0));
    UUID automaticTask =
        tasks
            .create(
                team.member(),
                team.first(),
                new TaskRequests.Create(
                    "New triage", null, TaskStatus.TRIAGE, TaskType.TASK, null, null, null),
                UUID.randomUUID().toString())
            .id();
    pump();
    assertThat(
            db.queryForObject(
                "SELECT status FROM agent_run WHERE automatic_task_id=?",
                String.class,
                bytes(automaticTask)))
        .isEqualTo("SUCCEEDED");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_model_usage c JOIN agent_run r ON r.id=c.run_id WHERE"
                    + " r.automatic_task_id=?",
                Long.class,
                bytes(automaticTask)))
        .isPositive();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM agent_model_call WHERE run_id IN (SELECT id FROM agent_run"
                    + " WHERE project_id=?)",
                Long.class,
                bytes(team.first())))
        .isZero();
  }

  @Test
  void queuedRotationAndRevocationBecomeDurableFailuresWithoutSending() {
    var run =
        runs.create(
            team.member(),
            team.first(),
            new AgentRuns.Request("Queue fixture"),
            UUID.randomUUID().toString());
    teams.updateConnection(
        team.admin(),
        team.workspace(),
        new TeamModels.ConnectionUpdate(
            1, "ZAI", "glm-4.7", List.of("glm-4.7"), true, 1000, 1, "rotation-fixture"));
    int before = MODEL.getRequestCount();
    pump();
    assertThat(runs.get(team.member(), team.first(), run.id()).errorCode())
        .isEqualTo("MODEL_CONNECTION_CHANGED");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
    var another =
        runs.create(
            team.member(),
            team.first(),
            new AgentRuns.Request("Revoke fixture"),
            UUID.randomUUID().toString());
    db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(team.first()),
        bytes(team.member().userId()));
    pump();
    assertThat(
            db.queryForObject(
                "SELECT error_code FROM agent_run WHERE id=?", String.class, bytes(another.id())))
        .isEqualTo("FORBIDDEN");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
  }

  @Test
  void actualCheckpointRecoveryRechecksRotationAndMembership() throws Exception {
    for (String action : List.of("rotate", "revoke")) {
      UUID c = conversations.create(team.member(), team.first()).id();
      var turn =
          conversations.submit(
              team.member(),
              c,
              new ConversationService.Submit("Checkpoint fixture", 0),
              UUID.randomUUID().toString(),
              null);
      var dir =
          java.nio.file.Path.of(
              "target/team-model-governance",
              LocalDate.now().toString(),
              "checkpoint-" + turn.runId());
      java.nio.file.Files.createDirectories(dir);
      var child =
          new ProcessBuilder(
                  System.getProperty("java.home") + "/bin/java",
                  "-Xmx192m",
                  "-XX:ActiveProcessorCount=2",
                  "-cp",
                  System.getProperty("java.class.path"),
                  TeamModelCrashWorker.class.getName(),
                  turn.runId().toString(),
                  MODEL.url("/chat/completions").toString())
              .redirectErrorStream(true)
              .redirectOutput(dir.resolve("child.log").toFile())
              .start();
      try {
        assertThat(child.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).as(dir.toString()).isEqualTo(86);
      } finally {
        if (child.isAlive()) child.destroyForcibly();
      }
      assertThat(
              db.queryForObject(
                  "SELECT call_count FROM agent_run_checkpoint WHERE run_id=?",
                  Long.class,
                  bytes(turn.runId())))
          .isEqualTo(1);
      int before = MODEL.getRequestCount();
      if (action.equals("rotate")) {
        var conn = teams.getConnection(team.admin(), team.workspace());
        teams.updateConnection(
            team.admin(),
            team.workspace(),
            new TeamModels.ConnectionUpdate(
                conn.version(),
                "ZAI",
                "glm-4.7",
                List.of("glm-4.7"),
                true,
                1000,
                1,
                "checkpoint-rotated"));
      } else
        db.update(
            "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
            bytes(team.first()),
            bytes(team.member().userId()));
      db.update(
          "UPDATE work_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE owner_ref=?",
          bytes(turn.runId()));
      pump();
      assertThat(
              db.queryForObject(
                  "SELECT error_code FROM agent_run WHERE id=?", String.class, bytes(turn.runId())))
          .isEqualTo(action.equals("rotate") ? "MODEL_CONNECTION_CHANGED" : "FORBIDDEN");
      assertThat(MODEL.getRequestCount()).isEqualTo(before);
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM team_model_usage WHERE run_id=?",
                  Long.class,
                  bytes(turn.runId())))
          .isEqualTo(1);
      TeamModelFixture.JSON.writeValue(
          dir.resolve("result.json").toFile(),
          Map.of(
              "action", action, "childExit", 86, "physicalCalls", 1, "additionalHttpRequests", 0));
    }
  }

  @Test
  void redisLossStopsGenerationWhileTaskCrudContinues() {
    var created =
        tasks.create(
            team.admin(),
            team.first(),
            new TaskRequests.Create(
                "CRUD during Redis outage", null, TaskStatus.TODO, TaskType.TASK, 1, null, null),
            UUID.randomUUID().toString());
    f.redis().delete(f.key(team.workspace()));
    var run =
        runs.create(
            team.admin(),
            team.first(),
            new AgentRuns.Request("Redis outage fixture"),
            UUID.randomUUID().toString());
    int before = MODEL.getRequestCount();
    pump();
    assertThat(runs.get(team.admin(), team.first(), run.id()).errorCode())
        .isEqualTo("MODEL_ADMISSION_MISSING");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
    var patch = new TaskRequests.Patch();
    patch.setTitle("CRUD remains writable");
    tasks.update(team.admin(), team.first(), created.id(), created.revision(), patch);
    assertThat(tasks.get(team.member(), team.first(), created.id()).title())
        .isEqualTo("CRUD remains writable");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state='PENDING'",Long.class,bytes(run.id()))).isZero();
  }

  @Test
  void httpManagementRequiresAdminAndDoesNotExposeKeyOrPrivateContent() throws Exception {
    String admin = "team-" + team.admin().userId(), member = "team-" + team.member().userId();
    mvc.perform(get("/workspaces/{w}/model-connection", team.workspace()).with(user(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasKey").value(true))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("synthetic-team-credential"))));
    mvc.perform(get("/workspaces/{w}/model-connection", team.workspace()).with(user(member)))
        .andExpect(status().isForbidden());
    mvc.perform(put("/projects/{p}/model-policy", team.first()).with(user(member)).with(csrf())
        .contentType("application/json").content(json.write(new TeamModels.PolicyUpdate(0,null,3,30000))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/workspaces/{w}/model-budget", team.workspace()).with(user(admin)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/projects/{p}/model-usage", team.first()).with(user(member)))
        .andExpect(status().isOk());
    db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(team.second()),
        bytes(team.admin().userId()));
    mvc.perform(get("/workspaces/{w}/model-usage", team.workspace()).with(user(admin)))
        .andExpect(status().isOk());
    mvc.perform(get("/projects/{p}/model-usage", team.second()).with(user(admin)))
        .andExpect(status().isForbidden());
  }
}
