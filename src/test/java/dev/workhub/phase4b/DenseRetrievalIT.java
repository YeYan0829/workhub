package dev.workhub.phase4b;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import dev.workhub.common.*;
import dev.workhub.contract.ContractSupport;
import dev.workhub.identity.*;
import dev.workhub.knowledge.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    properties = {
      "workhub.messaging.enabled=false",
      "workhub.retrieval.enabled=true",
      "workhub.embedding.enabled=true",
      "workhub.github.enabled=false",
      "workhub.bootstrap.login=",
      "debug=false"
    })
@AutoConfigureMockMvc
@Import(DenseRetrievalIT.Faults.class)
class DenseRetrievalIT {
  static final okhttp3.mockwebserver.MockWebServer MODEL = modelServer();
  static final int CONFIG = 2000 + new java.security.SecureRandom().nextInt(1000000000);
  static final String VECTOR_INDEX = "p4b-vector-" + UUID.randomUUID();
  static final java.util.concurrent.atomic.AtomicInteger CALLS =
      new java.util.concurrent.atomic.AtomicInteger();
  static volatile int faultStatus;
  static volatile String invalidResponse;
  static volatile Runnable duringEmbedding;

  static okhttp3.mockwebserver.MockWebServer modelServer() {
    var server = new okhttp3.mockwebserver.MockWebServer();
    server.setDispatcher(
        new okhttp3.mockwebserver.Dispatcher() {
          public okhttp3.mockwebserver.MockResponse dispatch(
              okhttp3.mockwebserver.RecordedRequest request) {
            CALLS.incrementAndGet();
            if (duringEmbedding != null) {
              var hook = duringEmbedding;
              duringEmbedding = null;
              hook.run();
            }
            if (faultStatus != 0) {
              int status = faultStatus;
              faultStatus = 0;
              return new okhttp3.mockwebserver.MockResponse().setResponseCode(status).setBody("{}");
            }
            if (invalidResponse != null) {
              String body = invalidResponse;
              invalidResponse = null;
              return new okhttp3.mockwebserver.MockResponse().setBody(body);
            }
            String body = request.getBody().clone().readUtf8();
            boolean auth =
                body.contains("tokenTimeout")
                    || body.contains("登录")
                    || body.contains("authentication");
            String vector = auth ? "[1,0,0,0.1]" : "[0,1,0,0.1]";
            return new okhttp3.mockwebserver.MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":"
                        + vector
                        + "}]},\"usage\":{\"total_tokens\":10}}");
          }
        });
    try {
      server.start();
      return server;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static final String INDEX = "p4-it-" + UUID.randomUUID();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            "jdbc:mysql://127.0.0.1:"
                + ContractSupport.env("MYSQL_PORT", "13306")
                + "/workhub_phase4b_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("spring.rabbitmq.virtual-host", () -> "phase4b_test");
    p.add("workhub.retrieval.index", () -> INDEX);
    p.add("workhub.embedding.config-id", () -> CONFIG);
    p.add("workhub.embedding.dimensions", () -> 4);
    p.add("workhub.embedding.endpoint", () -> MODEL.url("/embeddings").toString());
    p.add("workhub.embedding.api-key", () -> "fixture-only");
    p.add("workhub.embedding.run-id", () -> "fixture-" + VECTOR_INDEX);
    p.add("workhub.embedding.index", () -> VECTOR_INDEX);
  }

  @Autowired JdbcTemplate db;
  @Autowired ProjectService projects;
  @Autowired TaskService tasks;
  @Autowired ContentService content;
  @Autowired KnowledgeService service;
  @Autowired KnowledgeReads reads;
  @Autowired TextGenerations generations;
  @Autowired SourceText sources;
  @Autowired SourceObligations obligations;
  @Autowired WorkJobExecutor executor;
  @Autowired WorkJobRepository jobs;
  @Autowired OutboxRepository outbox;
  @Autowired RabbitPublisher rabbit;
  @Autowired RetryPolicy retry;
  @Autowired PlatformTransactionManager manager;
  @Autowired Json json;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc http;
  @Autowired FaultIndex index;
  @Autowired DenseSearch dense;
  @Autowired CloudEmbeddings embeddings;
  @Autowired DenseConfiguration config;
  @Autowired org.springframework.core.env.Environment environment;
  ActorContext actor, member;
  UUID project, workspace;
  Connection connection;
  Channel channel;

  @BeforeEach
  void setup() throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class))
        .isEqualTo("workhub_phase4b_test");
    actor = new ActorContext(UUID.randomUUID());
    member = new ActorContext(UUID.randomUUID());
    workspace = UUID.randomUUID();
    for (var a : List.of(actor, member))
      db.update(
          "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",
          bytes(a.userId()),
          login(a),
          "Retrieval tester");
    db.update("INSERT INTO workspace(id,name) VALUES (?,'Phase 4')", bytes(workspace));
    for (var a : List.of(actor, member))
      db.update(
          "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",
          bytes(workspace),
          bytes(a.userId()));
    project =
        projects.create(actor, workspace, "Search fixture", UUID.randomUUID().toString()).id();
    projects.member(actor, project, member.userId(), ProjectRole.MEMBER, true, revision());
    var factory = new ConnectionFactory();
    factory.setHost("127.0.0.1");
    factory.setPort(Integer.parseInt(ContractSupport.env("RABBITMQ_PORT", "15672")));
    factory.setUsername(ContractSupport.env("RABBITMQ_USER", "workhub"));
    factory.setPassword(ContractSupport.required("RABBITMQ_PASSWORD"));
    factory.setVirtualHost("phase4b_test");
    connection = factory.newConnection("phase4b-real-transport");
    channel = connection.createChannel();
    channel.exchangeDeclare(RabbitConfiguration.EXCHANGE, "direct", true);
    for (String q :
        List.of("knowledge.index", "knowledge.embedding", "business.work", "work.failed")) {
      channel.queueDeclare(q, true, false, false, null);
      channel.queueBind(q, RabbitConfiguration.EXCHANGE, q);
    }
    index.failureMode = null;
    faultStatus = 0;
    invalidResponse = null;
    duringEmbedding = null;
  }

  @AfterEach
  void close() throws Exception {
    if (connection != null) connection.close();
    index.failureMode = null;
    duringEmbedding = null;
    faultStatus = 0;
    invalidResponse = null;
  }

  String login(ActorContext a) {
    return "p4" + a.userId();
  }

  long revision() {
    return projects.get(actor, project).businessRevision();
  }

  ContentRepository.Document doc(String title, String body) {
    return content.createDocument(
        actor,
        project,
        ContentRepository.DocumentKind.DOCUMENT,
        title,
        body,
        UUID.randomUUID().toString());
  }

  Task task(String title, String body) {
    return tasks.create(
        actor,
        project,
        new TaskRequests.Create(title, body, null, null, null, null, null),
        UUID.randomUUID().toString());
  }

  UUID source(UUID owner, String kind) {
    return db.queryForObject(
        "SELECT id FROM knowledge_source WHERE " + kind + "=?",
        (r, n) -> read(r, "id"),
        bytes(owner));
  }

  void due() {
    db.update(
        "UPDATE work_job SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
            + " project_id=? AND status='RETRY_WAIT'",
        bytes(project));
    db.update(
        "UPDATE outbox_message o JOIN work_job j ON j.id=o.job_id SET"
            + " o.available_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE j.project_id=? AND"
            + " o.consume_disposition='UNHANDLED'",
        bytes(project));
  }

  void pump() throws Exception {
    var publisher = new OutboxPublisher(outbox, rabbit, retry);
    var consumer = new WorkJobConsumer(executor);
    for (int turn = 0; turn < 60; turn++) {
      due();
      boolean changed = false;
      for (int i = 0; i < 250 && publisher.publishOne(); i++) changed = true;
      for (int i = 0; i < 500; i++) {
        var delivery = channel.basicGet("knowledge.index", false);
        String route = "knowledge.index";
        if (delivery == null) {
          delivery = channel.basicGet("knowledge.embedding", false);
          route = "knowledge.embedding";
        }
        if (delivery == null) break;
        changed = true;
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setMessageId(delivery.getProps().getMessageId());
        properties.setReceivedRoutingKey(route);
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        consumer.receive(
            new org.springframework.amqp.core.Message(delivery.getBody(), properties), channel);
        assertThat(channel.isOpen()).as("production consumer committed and ACKed").isTrue();
      }
      long unfinished =
          db.queryForObject(
              "SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler IN"
                  + " ('INDEX_TEXT','PUBLISH_TEXT','EMBED_CHUNK','BUILD_VECTOR') AND status NOT IN"
                  + " ('DONE','FAILED')",
              Long.class,
              bytes(project));
      if (unfinished == 0 && !changed) return;
    }
    fail("Index pipeline did not drain");
  }

  void evidence(String name, Object value) throws Exception {
    Path p =
        Path.of(
            System.getProperty("phase4b.evidence-dir", "target/evidence/phase4b/scenarios"),
            name + ".json");
    Files.createDirectories(p.getParent());
    Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  @Test
  void boundedBatchesRetainAllCitationsAndDetectChangesBetweenSnapshots() throws Exception {
    var documents = new ArrayList<ContentRepository.Document>();
    for (int i = 0; i < 35; i++)
      documents.add(doc("authentication batch " + i, "tokenTimeout current evidence " + i));
    pump();
    var candidates = index.lexical(project, "tokenTimeout", 200);
    var current = reads.hydrate(member, project, candidates.hits(), 200, CONFIG);
    assertThat(current.results()).hasSize(35);
    assertThat(current.results().stream().map(KnowledgeReads.Evidence::ownerId))
        .containsExactlyInAnyOrderElementsOf(documents.stream().map(ContentRepository.Document::id).toList());
    // The business index retains inactive intermediate TEXT documents; they must be filtered.
    assertThat(current.dropped()).isPositive();
    reads.validate(member, project, current.results(), CONFIG);
    var changed = documents.get(34);
    content.editDocument(
        actor,
        project,
        changed.id(),
        changed.revision(),
        changed.title(),
        "updated tokenTimeout evidence");
    assertThatThrownBy(() -> reads.validate(member, project, current.results(), CONFIG))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("资料");
    pump();
    var fresh =
        reads.hydrate(
            member, project, index.lexical(project, "tokenTimeout", 200).hits(), 200, CONFIG);
    assertThat(fresh.results()).hasSize(35);
    projects.member(actor, project, member.userId(), ProjectRole.MEMBER, false, revision());
    assertThatThrownBy(() -> reads.validate(member, project, fresh.results(), CONFIG))
        .isInstanceOf(ApiException.class);
    evidence(
        "batch-concurrent-snapshots",
        Map.of("sources", 35, "updateRejected", true, "revocationRejected", true));
  }

  @Test
  void realDatabaseMqEsPublishVectorAndCanonicalCitations() throws Exception {
    var document = doc("登录模块", "tokenTimeout：authentication 回调与登录重试");
    doc("支付模块", "payment and invoice reconciliation");
    pump();
    var result = dense.search(member, project, "authentication", 10, "DENSE");
    assertThat(result.strategy()).isEqualTo("DENSE");
    assertThat(result.results().get(0).ownerId()).isEqualTo(document.id());
    assertThat(result.vectorSources()).isEqualTo(2);
    assertThat(result.results().get(0).text()).contains("tokenTimeout");
    var hybrid = dense.search(member, project, "tokenTimeout", 10, "HYBRID");
    assertThat(hybrid.results().get(0).ownerId()).isEqualTo(document.id());
    var source = source(document.id(), "document_id");
    var gen =
        db.queryForMap(
            "SELECT g.* FROM source_index_state i JOIN index_generation g ON"
                + " g.id=i.active_generation_id WHERE i.source_id=? AND i.config_id=?",
            bytes(source),
            CONFIG);
    assertThat(gen.get("stage")).isEqualTo("VECTOR");
    UUID canonical =
        uuid(
            (byte[])
                db.queryForMap(
                        "SELECT canonical_generation_id FROM index_generation WHERE id=?",
                        gen.get("canonical_generation_id"))
                    .get("canonical_generation_id"));
    assertThat(gen.get("manifest_hash"))
        .isEqualTo(generations.generation(canonical).get("manifest_hash"));
    http.perform(
            get("/projects/" + project + "/knowledge/search")
                .param("q", "登录")
                .param("strategy", "HYBRID")
                .with(user(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configId").value(CONFIG));
    evidence(
        "normal",
        Map.of(
            "dense",
            result,
            "hybrid",
            hybrid,
            "model",
            "loopback deterministic fixture; not real model quality",
            "infrastructure",
            "real MySQL/RabbitMQ/Elasticsearch"));
  }

  @Test
  void updatesDeletesAndPermissionsFilterOldVectors() throws Exception {
    var document = doc("登录模块", "tokenTimeout before update");
    pump();
    content.editDocument(
        actor, project, document.id(), document.revision(), "登录模块", "payment updated body");
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").results()).isEmpty();
    pump();
    assertThat(dense.search(member, project, "payment", 10, "DENSE").results().get(0).text())
        .contains("updated body");
    var current = content.getDocument(actor, project, document.id());
    content.deleteDocument(actor, project, document.id(), current.revision());
    db.update("UPDATE knowledge_source SET active=TRUE WHERE document_id=?", bytes(document.id()));
    assertThat(dense.search(member, project, "payment", 10, "DENSE").results()).isEmpty();
    var other = projects.create(actor, workspace, "isolated", UUID.randomUUID().toString()).id();
    content.createDocument(
        actor,
        other,
        ContentRepository.DocumentKind.DOCUMENT,
        "foreign",
        "tokenTimeout",
        UUID.randomUUID().toString());
    pump();
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").results()).isEmpty();
    duringEmbedding =
        () ->
            projects.member(actor, project, member.userId(), ProjectRole.MEMBER, false, revision());
    assertThatThrownBy(() -> dense.search(member, project, "tokenTimeout", 10, "DENSE"))
        .isInstanceOf(ApiException.class);
    evidence(
        "invalidation-isolation",
        Map.of(
            "updatedAndDeletedEvidenceExcluded",
            true,
            "sourceActiveMismatchExcluded",
            true,
            "projectFilter",
            true,
            "revokedDuringModelIo",
            true));
  }

  @Test
  void providerTransientRetryPermanentFailureAndLexicalFallback() throws Exception {
    faultStatus = 429;
    var document = doc("登录模块", "tokenTimeout retry");
    pump();
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").vectorSources())
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM embedding_call WHERE project_id=? AND"
                    + " error_code='EMBEDDING_HTTP_429'",
                Integer.class,
                bytes(project)))
        .isEqualTo(1);
    faultStatus = 503;
    var fallback = dense.search(member, project, "tokenTimeout", 10, "HYBRID");
    assertThat(fallback.strategy()).isEqualTo("BM25");
    assertThat(fallback.degradedReason()).isEqualTo("EMBEDDING_HTTP_503");
    assertThat(fallback.results()).hasSize(1);
    assertThat(fallback.retrievalComplete()).isFalse();
    invalidResponse = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0,0,0,0]}]}}";
    doc("bad model", "zero vector scenario");
    pump();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='EMBED_CHUNK' AND"
                    + " status='FAILED'",
                Integer.class,
                bytes(project)))
        .isEqualTo(1);
    assertThat(dense.search(member, project, "zero", 10, "BM25").results()).hasSize(1);
    invalidResponse = "not JSON";
    assertThatThrownBy(() -> embeddings.embed(project, "query", "test"))
        .isInstanceOf(WorkFailure.class)
        .hasMessage("EMBEDDING_INVALID_RESPONSE");
    evidence(
        "model-failure",
        Map.of(
            "transientRetried",
            true,
            "zeroVectorParked",
            true,
            "lexicalFallback",
            fallback,
            "invalidJsonPermanent",
            true));
  }

  @Test
  void embeddingArrivingAfterSourceUpdateCannotSaveOldArtifact() throws Exception {
    var document = doc("登录", "tokenTimeout old");
    duringEmbedding =
        () ->
            content.editDocument(
                actor, project, document.id(), document.revision(), "登录", "payment new");
    pump();
    var result = dense.search(member, project, "payment", 10, "DENSE");
    assertThat(result.results().get(0).text()).contains("payment new");
    String oldHash = Inputs.hash("登录\ntokenTimeout old");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM embedding_artifact WHERE source_id=? AND input_hash=?",
                Integer.class,
                bytes(source(document.id(), "document_id")),
                oldHash))
        .isZero();
    evidence(
        "late-embedding",
        Map.of(
            "sourceUpdateDuringRemoteCall",
            true,
            "staleArtifactNotStored",
            true,
            "newRevisionRetrieved",
            result));
  }

  @Test
  void partialVectorBulkFailureThenRebuildReusesFrozenArtifacts() throws Exception {
    index.failureMode = "PARTIAL_BAD";
    var document = doc("登录", "tokenTimeout body\n" + "long content\n".repeat(260));
    pump();
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").vectorSources()).isZero();
    assertThat(dense.search(member, project, "tokenTimeout", 10, "BM25").results()).hasSize(1);
    int calls = CALLS.get();
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 100),
        UUID.randomUUID().toString());
    pump();
    assertThat(CALLS.get()).isEqualTo(calls);
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").vectorSources())
        .isEqualTo(1);
    evidence(
        "partial-vector-recovery",
        Map.of(
            "realStrictMappingItemFailure",
            true,
            "textRemainedReadable",
            true,
            "freshGenerationReusedArtifacts",
            true));
  }

  @Test
  void lostResponseDuplicatePublishAndIndexLossRecoverWithoutNewEmbeddings() throws Exception {
    index.failureMode = "LOST_RESPONSE";
    doc("登录", "tokenTimeout immutable");
    pump();
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").vectorSources())
        .isEqualTo(1);
    int calls = CALLS.get();
    var client = java.net.http.HttpClient.newHttpClient();
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:19200/" + VECTOR_INDEX))
            .DELETE()
            .build();
    assertThat(
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 100),
        UUID.randomUUID().toString());
    pump();
    assertThat(CALLS.get()).isEqualTo(calls);
    assertThat(dense.search(member, project, "tokenTimeout", 10, "DENSE").vectorSources())
        .isEqualTo(1);
    evidence(
        "duplicate-index-loss",
        Map.of(
            "responseLostAfterActualEsWrite",
            true,
            "createConflictReused",
            true,
            "deletedRandomTestIndex",
            true,
            "cachedArtifactsRebuilt",
            true));
  }

  @Test
  void modelSpaceCannotBeChangedUnderExistingConfigId() {
    assertThatThrownBy(
            () ->
                new DenseConfiguration(
                    db,
                    json,
                    CONFIG,
                    3,
                    "different-model",
                    "dashscope",
                    MODEL.url("/embeddings").toString(),
                    "fixture-only",
                    VECTOR_INDEX,
                    config.instruction,
                    false,
                    config.runId,
                    java.math.BigDecimal.ONE,
                    72))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another model/space");
  }

  @Test
  void nativeQueryDocumentRolesAndDimensionsAreChecked() throws Exception {
    while (MODEL.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {}
    embeddings.embed(project, "document", "synthetic document");
    embeddings.embed(project, "query", "synthetic query");
    var docRequest = mapper.readTree(MODEL.takeRequest().getBody().readUtf8());
    var queryRequest = mapper.readTree(MODEL.takeRequest().getBody().readUtf8());
    assertThat(docRequest.at("/parameters/text_type").asText()).isEqualTo("document");
    assertThat(docRequest.at("/parameters/instruct").isMissingNode()).isTrue();
    assertThat(queryRequest.at("/parameters/text_type").asText()).isEqualTo("query");
    assertThat(queryRequest.at("/parameters/instruct").asText()).isEqualTo(config.instruction);
    invalidResponse = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[1,2]}]}}";
    assertThatThrownBy(() -> embeddings.embed(project, "query", "wrong dimension"))
        .isInstanceOf(WorkFailure.class)
        .hasMessage("EMBEDDING_DIMENSION_MISMATCH");
    invalidResponse = "{\"output\":{\"embeddings\":[{\"text_index\":1,\"embedding\":[1,0,0,0]}]}}";
    assertThatThrownBy(() -> embeddings.embed(project, "query", "wrong row"))
        .isInstanceOf(WorkFailure.class)
        .hasMessage("EMBEDDING_INVALID_RESPONSE");
    evidence(
        "model-contract",
        Map.of(
            "queryAndDocumentRoles", true, "dimensionsValidated", true, "rowOrderValidated", true));
  }

  @Test
  void persistentBudgetStopsCallsBeforeTheyReachProvider() {
    var c =
        new DenseConfiguration(
            db,
            json,
            CONFIG + 1,
            4,
            "text-embedding-v4",
            "dashscope",
            "https://example.invalid/embeddings",
            "never-sent",
            "p4b-budget-" + UUID.randomUUID(),
            config.instruction,
            true,
            "budget-" + UUID.randomUUID(),
            new java.math.BigDecimal("0.000000100"),
            72);
    var budget = new EmbeddingBudget(db, c);
    budget.reserve(project, "query", "first", 1);
    assertThatThrownBy(() -> budget.reserve(project, "query", "second", 1))
        .isInstanceOf(WorkFailure.class)
        .hasMessage("EMBEDDING_BUDGET_EXHAUSTED");
    assertThat(
            db.queryForObject(
                "SELECT reserved_nanousd FROM embedding_budget WHERE run_id=?",
                Long.class,
                c.runId))
        .isEqualTo(72);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM embedding_call WHERE run_id=?", Integer.class, c.runId))
        .isEqualTo(1);
  }

  @Test
  void repeatedEmbeddingAndVectorDeliveriesDoNotCallProviderAgain() throws Exception {
    var document = doc("登录", "tokenTimeout replay");
    pump();
    UUID sid = source(document.id(), "document_id");
    var envelopes =
        db.query(
            "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=?"
                + " AND j.handler IN ('EMBED_CHUNK','PUBLISH_TEXT')",
            (r, n) -> outbox.find(read(r, "id"), false),
            bytes(sid));
    int calls = CALLS.get();
    long generationsBefore =
        db.queryForObject(
            "SELECT COUNT(*) FROM index_generation WHERE source_id=?", Long.class, bytes(sid));
    for (var e : envelopes)
      for (int n = 0; n < 2; n++)
        channel.basicPublish(
            RabbitConfiguration.EXCHANGE,
            e.destination(),
            new AMQP.BasicProperties.Builder().messageId(e.id().toString()).deliveryMode(2).build(),
            e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    pump();
    assertThat(CALLS.get()).isEqualTo(calls);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM index_generation WHERE source_id=?", Long.class, bytes(sid)))
        .isEqualTo(generationsBefore);
    evidence(
        "redelivery",
        Map.of("realBrokerReplayed", true, "additionalModelCalls", 0, "additionalGenerations", 0));
  }

  @TestConfiguration
  static class Faults {
    @Bean
    @Primary
    FaultIndex faultIndex(DenseConfiguration c, ObjectMapper mapper) {
      return new FaultIndex(c, mapper);
    }
  }

  static class FaultIndex extends DenseIndex {
    String failureMode;

    FaultIndex(DenseConfiguration c, ObjectMapper mapper) {
      super(c, mapper, "http://127.0.0.1:19200");
    }

    public void publish(List<TextIndex.Document> docs) {
      if (!"VECTOR".equals(docs.get(0).fields().get("stage"))) {
        super.publish(docs);
        return;
      }
      if ("PARTIAL_BAD".equals(failureMode)) {
        failureMode = null;
        var list = new ArrayList<>(docs);
        var f = new HashMap<>(list.get(1).fields());
        f.put("undeclared", "actual strict mapping failure");
        list.set(1, new TextIndex.Document(list.get(1).id(), f));
        super.publish(list);
        return;
      }
      super.publish(docs);
      if ("LOST_RESPONSE".equals(failureMode)) {
        failureMode = null;
        throw new WorkFailure(true, "TEST_LOST_RESPONSE");
      }
    }
  }
}
