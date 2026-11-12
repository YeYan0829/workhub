package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.*;
import dev.workhub.messaging.*;
import dev.workhub.work.ContentRepository;
import dev.workhub.work.ContentService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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
      "workhub.messaging.enabled=false",
      "workhub.retrieval.enabled=true",
      "workhub.embedding.enabled=true",
      "workhub.embedding.legacy-mode=false",
      "workhub.agent.enabled=true",
      "workhub.github.enabled=false",
      "workhub.bootstrap.login=",
      "workhub.model-admission.enabled=true",
      "debug=false",
      "logging.level.root=WARN",
      "workhub.mail.key-file=target/team-model-governance/embedding/mail.key"
    })
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TeamEmbeddingIT.Faults.class)
class TeamEmbeddingIT {
  static final MockWebServer CHAT = new MockWebServer();
  static final MockWebServer MODEL = new MockWebServer();

  static {
    try {
      MODEL.start();
      CHAT.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static final String INDEX = "team-text-test-" + UUID.randomUUID();

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
    p.add("workhub.team-model.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    p.add(
        "workhub.team-model.embedding-fixture-endpoint", () -> MODEL.url("/embedding").toString());
    p.add(
        "workhub.retrieval.es-url",
        () -> System.getenv().getOrDefault("TEAM_MODEL_ES_URL", "http://127.0.0.1:29200"));
    p.add("workhub.retrieval.index", () -> INDEX);
  }

  @Autowired TeamEmbeddings embeddings;
  @Autowired TeamEmbeddingCalls calls;
  @Autowired TeamModels models;
  @Autowired TeamDenseSearch search;
  @Autowired ContentService content;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper mapper;
  @Autowired Json json;
  @Autowired WorkJobExecutor executor;
  @Autowired OutboxRepository outbox;
  @Autowired MockMvc mvc;
  @Autowired dev.workhub.work.TaskService tasks;
  @Autowired FaultIndexes faultIndexes;
  @Autowired AgentRuns agentRuns;
  @Autowired org.springframework.beans.factory.ObjectProvider<CloudEmbeddings> legacy;
  TeamModelFixture f;
  TeamModelFixture.Team team;
  TeamEmbeddings.ConnectionView connection;
  AtomicInteger faults = new AtomicInteger();
  boolean missingUsage;
  String invalid;
  Runnable during;

  @BeforeEach
  void setup() {
    faultIndexes.mode = null;
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
                                        Map.of(
                                            "query",
                                            "tokenTimeout",
                                            "strategy",
                                            "DENSE",
                                            "limit",
                                            5))))));
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
                              "glm-4.7",
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
    f = new TeamModelFixture("http://127.0.0.1:1/unused", Clock.systemUTC());
    team = f.team();
    MODEL.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            if (during != null) {
              var action = during;
              during = null;
              action.run();
            }
            int status = faults.getAndSet(0);
            if (status != 0)
              return new MockResponse().setResponseCode(status).setHeader("Retry-After", "0");
            if (invalid != null) {
              var body = invalid;
              invalid = null;
              return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
            try {
              int dims =
                  mapper.readTree(r.getBody().readUtf8()).at("/parameters/dimension").asInt();
              var vector = new ArrayList<Integer>();
              for (int i = 0; i < dims; i++) vector.add(i == 0 ? 1 : 0);
              var body = new LinkedHashMap<String, Object>();
              body.put(
                  "output",
                  Map.of("embeddings", List.of(Map.of("text_index", 0, "embedding", vector))));
              if (!missingUsage) body.put("usage", Map.of("total_tokens", 10));
              return new MockResponse()
                  .setHeader("Content-Type", "application/json")
                  .setBody(json.write(body));
            } catch (Exception e) {
              return new MockResponse().setResponseCode(500);
            }
          }
        });
    connection = configure(0, 64, "", true, "synthetic-team-embedding-key");
    initialize(connection.quota());
  }

  TeamEmbeddings.ConnectionView configure(
      long version, int dims, String instruction, boolean enabled, String key) {
    return embeddings.updateConnection(
        team.admin(),
        team.workspace(),
        new TeamEmbeddings.ConnectionUpdate(
            version,
            TeamEmbeddings.MODEL,
            dims,
            instruction,
            enabled,
            false,
            1000,
            1,
            key,
            "fixture-space"));
  }

  void initialize(ModelAdmission.Quota q) {
    var script = new org.springframework.data.redis.core.script.DefaultRedisScript<List>();
    script.setLocation(new org.springframework.core.io.ClassPathResource("model/admission.lua"));
    script.setResultType(List.class);
    String key = "workhub:model:{" + q.scope() + "}";
    long recovery = (long) Math.ceil(1000 * q.burst() / q.rate());
    var r =
        f.redis()
            .execute(
                script,
                List.of(key, key + ":init"),
                "initialize",
                "team-v1|" + q.version() + "|" + q.rate() + "|" + q.burst(),
                "" + q.rate(),
                "" + q.burst(),
                "" + recovery,
                UUID.randomUUID().toString());
    assertThat(r.get(0)).isIn("INITIALIZED", "EXISTS");
    try {
      Thread.sleep(recovery + 2);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  void enable(UUID p) {
    embeddings.updatePolicy(
        team.admin(),
        p,
        new TeamEmbeddings.PolicyUpdate(0, true, connection.defaultSpace().configId()));
  }

  TeamEmbeddings.Ticket ticket(UUID p) {
    return embeddings.ticket(p, connection.defaultSpace().configId(), team.member().userId());
  }

  byte[] query(UUID p, UUID op) {
    return calls.embed(ticket(p), op, "query", "synthetic query", () -> {});
  }

  void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable a, String code) {
    assertThatThrownBy(a).hasFieldOrPropertyWithValue("code", code);
  }

  ContentRepository.Document doc(UUID p, String text) {
    return content.createDocument(
        team.admin(),
        p,
        ContentRepository.DocumentKind.DOCUMENT,
        "Synthetic document",
        text,
        UUID.randomUUID().toString());
  }

  void pump() {
    for (int i = 0; i < 150; i++) {
      var rows =
          db.queryForList(
              "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE"
                  + " j.project_id IN (?,?) AND j.handler IN"
                  + " ('INDEX_TEXT','PUBLISH_TEXT','TEAM_EMBED_REBUILD','TEAM_EMBED_TEXT','TEAM_EMBED_CHUNK','TEAM_EMBED_PUBLISH','RUN_AGENT')"
                  + " AND o.consume_disposition='UNHANDLED' AND j.status NOT IN ('DONE','FAILED')"
                  + " ORDER BY j.created_at,j.id",
              bytes(team.first()),
              bytes(team.second()));
      if (rows.isEmpty()) return;
      for (var row : rows) {
        var e = outbox.find(uuid((byte[]) row.get("id")), false);
        db.update(
            "UPDATE work_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1"
                + " SECOND,next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
            bytes(e.jobId()));
        db.update(
            "UPDATE outbox_message SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
            bytes(e.id()));
        e = outbox.find(e.id(), false);
        executor.handle(
            e.id().toString(), e.destination(), e.wire().getBytes(StandardCharsets.UTF_8));
      }
    }
    throw new AssertionError("Embedding jobs did not drain");
  }

  @AfterEach
  void close() {
    f.close();
  }

  @AfterAll
  static void stop() throws Exception {
    MODEL.close();
    CHAT.close();
  }

  @Test
  void supplierWorkspaceCannotInjectAnEndpoint() {
    assertThat(TeamEmbeddings.nativeEndpoint("test-space"))
        .isEqualTo(
            "https://test-space.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding");
    for (String id :
        List.of(
            "127.0.0.1",
            "a@127.0.0.1",
            "a/path",
            "a%2eexample",
            "a.example.com",
            "-invalid",
            "invalid-")) code(() -> TeamEmbeddings.nativeEndpoint(id), "VALIDATION_ERROR");
  }

  @Test
  void adminPermissionsEncryptionRedactionAndSpaceIdentity() {
    assertThat(legacy.getIfAvailable()).isNull();
    code(() -> embeddings.getConnection(team.member(), team.workspace()), "FORBIDDEN");
    var foreign = f.team();
    code(() -> embeddings.getConnection(foreign.admin(), team.workspace()), "FORBIDDEN");
    assertThat(json.write(connection))
        .doesNotContain("synthetic-team-embedding-key", "cipher", "price", "budget");
    assertThat(
            db.queryForObject(
                "SELECT key_cipher FROM team_embedding_connection WHERE workspace_id=?",
                String.class,
                bytes(team.workspace())))
        .doesNotContain("synthetic-team-embedding-key");
    var rotated = configure(1, 64, "", true, "synthetic-embedding-rotated");
    assertThat(rotated.defaultSpace()).isEqualTo(connection.defaultSpace());
    var disabled = configure(2, 64, "", false, null);
    assertThat(disabled.defaultSpace()).isEqualTo(connection.defaultSpace());
    code(() -> configure(2, 64, "", true, null), "MODEL_CONFIG_CONFLICT");
    var changed = configure(3, 128, "", true, null);
    assertThat(changed.defaultSpace().configId())
        .isNotEqualTo(connection.defaultSpace().configId());
    var instruction = configure(4, 128, "Retrieve issues", true, null);
    assertThat(instruction.defaultSpace().configId())
        .isNotEqualTo(changed.defaultSpace().configId());
    assertThat(embeddings.spaces(team.admin(), team.workspace())).hasSize(3);
    code(
        () ->
            embeddings.updatePolicy(
                foreign.admin(),
                foreign.first(),
                new TeamEmbeddings.PolicyUpdate(0, true, connection.defaultSpace().configId())),
        "FORBIDDEN");
  }

  @Test
  void rawUsageMissingUsageAndNoAmountConfiguration() {
    enable(team.first());
    missingUsage = true;
    UUID op = UUID.randomUUID();
    assertThat(query(team.first(), op)).hasSize(64 * 4);
    var r = db.queryForMap("SELECT * FROM team_embedding_call WHERE operation_id=?", bytes(op));
    assertThat(r.get("state")).isEqualTo("COMPLETE");
    assertThat(r.get("usage_state")).isEqualTo("UNKNOWN");
    assertThat(r.get("input_tokens")).isNull();
    missingUsage = false;
    query(team.first(), UUID.randomUUID());
    assertThat(models.projectUsage(team.member(), team.first(), null, 100))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.get("capability")).isEqualTo("EMBEDDING"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM embedding_call WHERE project_id=?",
                Long.class,
                bytes(team.first())))
        .isZero();
  }

  @Test
  void noConfigurationAndNoProjectOptInCannotUseDeploymentKey() {
    int before = MODEL.getRequestCount();
    var other = f.team();
    code(
        () ->
            embeddings.ticket(
                other.first(), connection.defaultSpace().configId(), other.member().userId()),
        "FORBIDDEN");
    code(() -> ticket(team.first()), "EMBEDDING_POLICY_CHANGED");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
  }

  @Test
  void knownHttpFailuresRetryButUnknownOperationNeverReplays() {
    enable(team.first());
    faults.set(429);
    UUID op = UUID.randomUUID();
    query(team.first(), op);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE operation_id=?",
                Long.class,
                bytes(op)))
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE operation_id=? AND state='COMPLETE'"
                    + " AND http_status=429",
                Long.class,
                bytes(op)))
        .isEqualTo(1);
    int before = MODEL.getRequestCount();
    assertThatThrownBy(() -> query(team.first(), op)).hasMessage("EMBEDDING_RESULT_UNKNOWN");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
    invalid = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[1,0]}]}}";
    assertThatThrownBy(() -> query(team.first(), UUID.randomUUID()))
        .hasMessage("EMBEDDING_DIMENSION_MISMATCH");
  }

  @Test
  void rotationAndRevocationWhileWaitingStopHttpWithoutMoneyHold() throws Exception {
    enable(team.first());
    String k = "workhub:model:{" + connection.quota().scope() + "}";
    f.redis().opsForHash().put(k, "cooldown", "" + (System.currentTimeMillis() + 30000));
    var pool = Executors.newSingleThreadExecutor();
    int before = MODEL.getRequestCount();
    try {
      var future =
          pool.submit(
              () -> {
                try {
                  query(team.first(), UUID.randomUUID());
                  return "SENT";
                } catch (WorkFailure e) {
                  return e.getMessage();
                }
              });
      TeamModelGovernanceIT.await(
          () ->
              db.queryForObject(
                      "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=?",
                      Long.class,
                      bytes(team.first()))
                  > 0);
      configure(1, 64, "", true, "synthetic-embedding-rotated");
      assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("EMBEDDING_CONFIG_CHANGED");
      assertThat(MODEL.getRequestCount()).isEqualTo(before);
      assertThat(
              db.queryForObject(
                  "SELECT state FROM team_embedding_call WHERE project_id=?",
                  String.class,
                  bytes(team.first())))
          .isEqualTo("NOT_SENT");
    } finally {
      pool.shutdownNow();
    }
    var current = embeddings.getConnection(team.admin(), team.workspace());
    initialize(current.quota());
    f.redis().opsForHash().put(k, "cooldown", "0");
    db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(team.first()),
        bytes(team.member().userId()));
    code(() -> ticket(team.first()), "FORBIDDEN");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
  }

  @Test
  void sharedGenerationCredentialUsesTheSameQuotaWithoutCopyingKey() {
    f.configure(team, 1000, 1);
    models.updateConnection(
        team.admin(),
        team.workspace(),
        new TeamModels.ConnectionUpdate(
            1,
            "DASHSCOPE",
            "qwen3.7-plus",
            List.of("qwen3.7-plus"),
            true,
            1000,
            1,
            "synthetic-shared-key"));
    var c =
        embeddings.updateConnection(
            team.admin(),
            team.workspace(),
            new TeamEmbeddings.ConnectionUpdate(
                1, TeamEmbeddings.MODEL, 64, "", true, true, 1000, 1, null, "fixture-space"));
    assertThat(c.quota().scope()).isEqualTo(TeamModels.scope(team.workspace()));
    assertThat(
            db.queryForObject(
                "SELECT key_cipher FROM team_embedding_connection WHERE workspace_id=?",
                String.class,
                bytes(team.workspace())))
        .isNull();
    enable(team.first());
    query(team.first(), UUID.randomUUID());
    var old = ticket(team.first());
    models.updateConnection(
        team.admin(),
        team.workspace(),
        new TeamModels.ConnectionUpdate(
            2,
            "DASHSCOPE",
            "qwen3.7-plus",
            List.of("qwen3.7-plus"),
            true,
            1000,
            1,
            "synthetic-shared-rotated"));
    code(() -> embeddings.verify(old), "EMBEDDING_CONFIG_CHANGED");
  }

  @Test
  void csrfAndManagementHttpPermissions() throws Exception {
    String admin = "team-" + team.admin().userId(), member = "team-" + team.member().userId();
    mvc.perform(get("/workspaces/{w}/embedding-connection", team.workspace()).with(user(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultSpace.dimensions").value(64));
    mvc.perform(get("/workspaces/{w}/embedding-connection", team.workspace()).with(user(member)))
        .andExpect(status().isForbidden());
    String body =
        json.write(new TeamEmbeddings.PolicyUpdate(0, true, connection.defaultSpace().configId()));
    mvc.perform(
            put("/projects/{p}/embedding-policy", team.first())
                .with(user(admin))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/projects/{p}/embedding-policy", team.first())
                .with(user(member))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/projects/{p}/embedding-policy", team.first())
                .with(user(admin))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void realIndexRebuildActivateQueryAndRotationReuseArtifacts() {
    doc(team.first(), "tokenTimeout canonical evidence");
    enable(team.first());
    code(
        () -> embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1)),
        "EMBEDDING_INDEX_NOT_READY");
    pump();
    var p = embeddings.policy(team.admin(), team.first());
    assertThat(p.readySources()).isEqualTo(p.eligibleSources()).isEqualTo(1);
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    assertThat(search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE").results())
        .hasSize(1);
    long documents =
        db.queryForObject(
            "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND purpose='document'",
            Long.class,
            bytes(team.first()));
    configure(1, 64, "", true, "synthetic-embedding-rotated");
    embeddings.rebuild(team.admin(), team.first(), new TeamEmbeddings.Version(2));
    pump();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND"
                    + " purpose='document'",
                Long.class,
                bytes(team.first())))
        .isEqualTo(documents);
    assertThat(search.search(team.member(), team.first(), "tokenTimeout", 5, "HYBRID").strategy())
        .isEqualTo("HYBRID");
  }

  @Test
  void parallelSpaceRebuildCatchesEditsDeletesAndSwitchesAtomically() {
    var first = doc(team.first(), "tokenTimeout old evidence");
    var deleted = doc(team.first(), "tokenTimeout obsolete evidence");
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    int old = connection.defaultSpace().configId();
    connection = configure(1, 128, "", true, null);
    embeddings.updatePolicy(
        team.admin(),
        team.first(),
        new TeamEmbeddings.PolicyUpdate(2, true, connection.defaultSpace().configId()));
    assertThat(search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE").configId())
        .isEqualTo(old);
    content.editDocument(
        team.admin(),
        team.first(),
        first.id(),
        first.revision(),
        "Synthetic document",
        "tokenTimeout current changed evidence");
    content.deleteDocument(team.admin(), team.first(), deleted.id(), deleted.revision());
    doc(team.first(), "tokenTimeout newly inserted");
    pump();
    var ready = embeddings.policy(team.admin(), team.first());
    assertThat(ready.eligibleSources()).isEqualTo(2);
    assertThat(ready.readySources()).isEqualTo(2);
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(3));
    var result = search.search(team.member(), team.first(), "tokenTimeout", 5, "HYBRID");
    assertThat(result.configId()).isEqualTo(connection.defaultSpace().configId());
    assertThat(result.results())
        .hasSize(2)
        .noneSatisfy(e -> assertThat(e.text()).contains("obsolete"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_space WHERE workspace_id=?",
                Long.class,
                bytes(team.workspace())))
        .isEqualTo(2);
  }

  @Test
  void sourceChangeDuringEmbeddingCannotPublishOldArtifact() {
    var d = doc(team.first(), "tokenTimeout stale body");
    enable(team.first());
    during =
        () ->
            content.editDocument(
                team.admin(),
                team.first(),
                d.id(),
                d.revision(),
                "Synthetic document",
                "tokenTimeout replacement body");
    pump();
    assertThat(embeddings.policy(team.admin(), team.first()).readySources()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM embedding_artifact WHERE project_id=? AND input_hash=?",
                Long.class,
                bytes(team.first()),
                Inputs.hash("Synthetic document\ntokenTimeout stale body")))
        .isZero();
  }

  @Test
  void disabledConnectionFallsBackLexicallyAndDoesNotExposeForeignProject() {
    doc(team.first(), "tokenTimeout current");
    doc(team.second(), "tokenTimeout foreign");
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    configure(1, 64, "", false, null);
    int before = MODEL.getRequestCount();
    var result = search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE");
    assertThat(result.strategy()).isEqualTo("BM25");
    assertThat(result.degradedReason()).isEqualTo("EMBEDDING_CONNECTION_DISABLED");
    assertThat(result.results())
        .hasSize(1)
        .allSatisfy(e -> assertThat(e.text()).doesNotContain("foreign"));
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Faults {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    FaultIndexes faultIndexes(
        ObjectMapper mapper,
        @org.springframework.beans.factory.annotation.Value("${workhub.retrieval.es-url}")
            String base) {
      return new FaultIndexes(mapper, base);
    }
  }

  static class FaultIndexes extends TeamVectorIndexes {
    String mode;

    FaultIndexes(ObjectMapper mapper, String base) {
      super(mapper, base);
    }

    @Override
    public void publish(TeamEmbeddings.Space s, List<TextIndex.Document> docs) {
      String fault = mode;
      mode = null;
      if ("PARTIAL_BAD".equals(fault)) {
        var changed = new ArrayList<>(docs);
        var fields = new LinkedHashMap<>(changed.get(1).fields());
        fields.put("undeclared", "actual strict mapping failure");
        changed.set(1, new TextIndex.Document(changed.get(1).id(), fields));
        super.publish(s, changed);
        return;
      }
      super.publish(s, docs);
      if ("LOST_RESPONSE".equals(fault)) throw new WorkFailure(true, "TEST_LOST_ES_RESPONSE");
    }
  }

  @Test
  void partialPublishAndLostResponseRecoverUsingCachedArtifacts() {
    doc(team.first(), "tokenTimeout long document\n" + "content line for chunking\n".repeat(250));
    enable(team.first());
    faultIndexes.mode = "PARTIAL_BAD";
    pump();
    assertThat(embeddings.policy(team.admin(), team.first()).readySources()).isZero();
    assertThat(embeddings.policy(team.admin(), team.first()).failedJobs()).isPositive();
    long before =
        db.queryForObject(
            "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND purpose='document'",
            Long.class,
            bytes(team.first()));
    faultIndexes.mode = "LOST_RESPONSE";
    embeddings.rebuild(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    pump();
    assertThat(embeddings.policy(team.admin(), team.first()).readySources()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND"
                    + " purpose='document'",
                Long.class,
                bytes(team.first())))
        .isEqualTo(before);
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    assertThat(search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE").results())
        .hasSize(1);
  }

  @Test
  void lostIndexRebuildDoesNotRepeatEmbeddingCalls() throws Exception {
    doc(team.first(), "tokenTimeout frozen source");
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    long before =
        db.queryForObject(
            "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND purpose='document'",
            Long.class,
            bytes(team.first()));
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            System.getenv()
                                    .getOrDefault("TEAM_MODEL_ES_URL", "http://127.0.0.1:29200")
                                + "/"
                                + connection.defaultSpace().indexName()))
                    .DELETE()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE").strategy())
        .isEqualTo("BM25");
    embeddings.rebuild(team.admin(), team.first(), new TeamEmbeddings.Version(2));
    pump();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND"
                    + " purpose='document'",
                Long.class,
                bytes(team.first())))
        .isEqualTo(before);
    var result = search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE");
    assertThat(result.strategy()).isEqualTo("DENSE");
    assertThat(result.results()).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE operation_id=?",
                Long.class,
                bytes(result.executionId())))
        .isEqualTo(1);
  }

  @Test
  void projectAnalysisCapabilitySurvivesEnablerRemovalWithoutImpersonation() {
    var task =
        tasks.create(
            team.member(),
            team.first(),
            new dev.workhub.work.TaskRequests.Create(
                "Embedding triage",
                "tokenTimeout analysis",
                dev.workhub.work.TaskStatus.TRIAGE,
                dev.workhub.work.TaskType.TASK,
                null,
                null,
                null),
            UUID.randomUUID().toString());
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    var grant = ActorContext.projectAnalysis(team.admin().userId(), team.first(), task.id());
    db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(team.first()),
        bytes(team.admin().userId()));
    var result = search.search(grant, team.first(), "tokenTimeout", 5, "DENSE");
    assertThat(result.strategy()).isEqualTo("DENSE");
    assertThat(
            db.queryForObject(
                "SELECT actor_id FROM team_embedding_call WHERE operation_id=?",
                byte[].class,
                bytes(result.executionId())))
        .isNull();
    db.update("UPDATE task SET status='TODO',triage_required=false WHERE id=?", bytes(task.id()));
    code(
        () -> embeddings.ticketForActor(team.first(), connection.defaultSpace().configId(), grant),
        "TRIAGE_NO_LONGER_REQUIRED");
  }

  @Test
  void redisLossAndAnotherTeamsCooldownNeverAllowUnmeteredHttp() {
    enable(team.first());
    String key = "workhub:model:{" + connection.quota().scope() + "}";
    f.redis().delete(key);
    int before = MODEL.getRequestCount();
    assertThatThrownBy(() -> query(team.first(), UUID.randomUUID()))
        .hasMessage("MODEL_ADMISSION_MISSING");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
    assertThat(
            db.queryForObject(
                "SELECT state FROM team_embedding_call WHERE project_id=?",
                String.class,
                bytes(team.first())))
        .isEqualTo("NOT_SENT");
    initialize(connection.quota());
    var other = f.team();
    var c =
        embeddings.updateConnection(
            other.admin(),
            other.workspace(),
            new TeamEmbeddings.ConnectionUpdate(
                0,
                TeamEmbeddings.MODEL,
                64,
                "",
                true,
                false,
                1000,
                1,
                "synthetic-other-embedding-key",
                "fixture-space"));
    initialize(c.quota());
    embeddings.updatePolicy(
        other.admin(),
        other.first(),
        new TeamEmbeddings.PolicyUpdate(0, true, c.defaultSpace().configId()));
    f.redis().opsForHash().put(key, "cooldown", "" + (System.currentTimeMillis() + 30000));
    calls.embed(
        embeddings.ticket(other.first(), c.defaultSpace().configId(), other.member().userId()),
        UUID.randomUUID(),
        "query",
        "independent",
        () -> {});
    assertThat(MODEL.getRequestCount()).isEqualTo(before + 1);
  }

  @Test
  void revokedDuringQueryCannotReadResults() {
    doc(team.first(), "tokenTimeout permission source");
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    during =
        () ->
            db.update(
                "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
                bytes(team.first()),
                bytes(team.member().userId()));
    code(() -> search.search(team.member(), team.first(), "tokenTimeout", 5, "DENSE"), "FORBIDDEN");
  }

  @Test
  void agentEvidenceToolUsesTeamEmbeddingAndUnifiedUsage() {
    doc(team.first(), "tokenTimeout source for agent");
    enable(team.first());
    pump();
    embeddings.activate(team.admin(), team.first(), new TeamEmbeddings.Version(1));
    f.configure(team, 1000, 1);
    var run =
        agentRuns.create(
            team.admin(),
            team.first(),
            new AgentRuns.Request("查阅 tokenTimeout 的相关项目资料"),
            UUID.randomUUID().toString());
    pump();
    assertThat(agentRuns.get(team.admin(), team.first(), run.id()).status()).isEqualTo("SUCCEEDED");
    var usage = models.projectUsage(team.admin(), team.first(), null, 100);
    assertThat(usage)
        .anySatisfy(row -> assertThat(row.get("capability")).isEqualTo("TEXT_GENERATION"));
    assertThat(usage).anySatisfy(row -> assertThat(row.get("capability")).isEqualTo("EMBEDDING"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM team_embedding_call WHERE project_id=? AND purpose='query'",
                Long.class,
                bytes(team.first())))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT payload FROM agent_trace WHERE run_id=? AND kind='TOOL' LIMIT 1",
                String.class,
                bytes(run.id())))
        .contains("executionId");
  }

  Process worker(java.nio.file.Path dir, String id) throws Exception {
    return new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Xmx192m",
            "-XX:ActiveProcessorCount=2",
            "-cp",
            System.getProperty("java.class.path"),
            TeamEmbeddingWorker.class.getName(),
            dir.toString(),
            id,
            MODEL.url("/embedding").toString())
        .redirectErrorStream(true)
        .redirectOutput(dir.resolve("child-" + id + ".log").toFile())
        .start();
  }

  @Test
  void twoRealJvmsShareEmbeddingQuotaAndAttributeBothProjects() throws Exception {
    connection =
        embeddings.updateConnection(
            team.admin(),
            team.workspace(),
            new TeamEmbeddings.ConnectionUpdate(
                1, TeamEmbeddings.MODEL, 64, "", true, false, 4, 2, null, "fixture-space"));
    initialize(connection.quota());
    enable(team.first());
    enable(team.second());
    var dir =
        java.nio.file.Path.of("target/team-model-governance/embedding/two-jvm-" + UUID.randomUUID())
            .toAbsolutePath();
    java.nio.file.Files.createDirectories(dir);
    for (int w = 0; w < 2; w++) {
      var inputs = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 6; i++)
        inputs.add(
            Map.of(
                "project",
                (i % 2 == 0 ? team.first() : team.second()).toString(),
                "actor",
                team.member().userId().toString(),
                "operation",
                UUID.randomUUID().toString(),
                "config",
                connection.defaultSpace().configId()));
      mapper.writeValue(dir.resolve("input-" + w + ".json").toFile(), inputs);
    }
    var children = List.of(worker(dir, "0"), worker(dir, "1"));
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (!java.nio.file.Files.exists(dir.resolve("ready-0"))
          || !java.nio.file.Files.exists(dir.resolve("ready-1"))) {
        assertThat(System.nanoTime()).isLessThan(deadline);
        Thread.sleep(30);
      }
      java.nio.file.Files.writeString(dir.resolve("go"), "go");
      for (var child : children) {
        assertThat(child.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isZero();
      }
      for (int w = 0; w < 2; w++) {
        List<Map<String, Object>> result =
            mapper.readValue(dir.resolve("result-" + w + ".json").toFile(), List.class);
        assertThat(result)
            .hasSize(6)
            .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SUCCEEDED"));
      }
      var rows =
          db.queryForList(
              "SELECT project_id,permit_at,http_status,input_tokens FROM team_embedding_call WHERE"
                  + " workspace_id=? ORDER BY permit_at",
              bytes(team.workspace()));
      assertThat(rows).hasSize(12);
      double tokens = 2;
      long last = ((Number) rows.get(0).get("permit_at")).longValue();
      for (var row : rows) {
        long now = ((Number) row.get("permit_at")).longValue();
        tokens = Math.min(2, tokens + (now - last) * 4 / 1000d);
        last = now;
        assertThat(tokens).isGreaterThanOrEqualTo(0.999);
        tokens--;
        assertThat(row.get("http_status")).isEqualTo(200);
      }
      assertThat(models.projectUsage(team.member(), team.first(), null, 100)).hasSize(6);
      assertThat(models.projectUsage(team.member(), team.second(), null, 100)).hasSize(6);
      mapper.writeValue(
          dir.resolve("summary.json").toFile(),
          Map.of(
              "jvms",
              2,
              "physicalCalls",
              12,
              "teamRate",
              4,
              "teamBurst",
              2,
              "usage",
              models.usage(team.admin(), team.workspace(), null, null, 100)));
    } finally {
      for (var child : children)
        if (child.isAlive()) {
          child.destroyForcibly();
          child.waitFor();
        }
    }
  }

  @Test
  void killedJvmAfterEmbeddingHttpCannotReplayItsUnknownOperation() throws Exception {
    enable(team.first());
    var dir =
        java.nio.file.Path.of(
                "target/team-model-governance/embedding/sent-crash-" + UUID.randomUUID())
            .toAbsolutePath();
    java.nio.file.Files.createDirectories(dir);
    UUID operation = UUID.randomUUID();
    mapper.writeValue(
        dir.resolve("input-0.json").toFile(),
        List.of(
            Map.of(
                "project",
                team.first().toString(),
                "actor",
                team.member().userId().toString(),
                "operation",
                operation.toString(),
                "config",
                connection.defaultSpace().configId())));
    while (MODEL.takeRequest(1, TimeUnit.MILLISECONDS) != null) {}
    MODEL.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
          }
        });
    java.nio.file.Files.writeString(dir.resolve("go"), "go");
    var child = worker(dir, "0");
    try {
      assertThat(MODEL.takeRequest(90, TimeUnit.SECONDS)).isNotNull();
      child.destroyForcibly();
      assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
    assertThat(
            db.queryForObject(
                "SELECT state FROM team_embedding_call WHERE operation_id=?",
                String.class,
                bytes(operation)))
        .isEqualTo("PENDING");
    int before = MODEL.getRequestCount();
    assertThatThrownBy(() -> query(team.first(), operation)).hasMessage("EMBEDDING_RESULT_UNKNOWN");
    assertThat(MODEL.getRequestCount()).isEqualTo(before);
    mapper.writeValue(
        dir.resolve("result.json").toFile(),
        Map.of(
            "actualHttpSent",
            true,
            "replayedRequests",
            0,
            "state",
            "PENDING",
            "usageFabricated",
            false));
  }
}
