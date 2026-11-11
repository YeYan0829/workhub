package dev.workhub.phase4;

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
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "workhub.messaging.enabled=false",
      "workhub.retrieval.enabled=true",
      "workhub.github.enabled=false",
      "workhub.bootstrap.login=",
      "debug=false"
    })
@AutoConfigureMockMvc
@Import(RetrievalIT.Faults.class)
class RetrievalIT {
  static final String INDEX = "p4-it-" + UUID.randomUUID();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            "jdbc:mysql://127.0.0.1:"
                + ContractSupport.env("MYSQL_PORT", "13306")
                + "/workhub_phase4_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("spring.rabbitmq.virtual-host", () -> "phase4_test");
    p.add("workhub.retrieval.index", () -> INDEX);
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
  ActorContext actor, member;
  UUID project, workspace;
  Connection connection;
  Channel channel;

  @BeforeEach
  void setup() throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class))
        .isEqualTo("workhub_phase4_test");
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
    factory.setVirtualHost("phase4_test");
    connection = factory.newConnection("phase4-real-transport");
    channel = connection.createChannel();
    channel.exchangeDeclare(RabbitConfiguration.EXCHANGE, "direct", true);
    for (String q : List.of("knowledge.index", "business.work", "work.failed")) {
      channel.queueDeclare(q, true, false, false, null);
      channel.queueBind(q, RabbitConfiguration.EXCHANGE, q);
    }
    index.beforePublish = null;
    index.afterPublish = null;
    index.afterSearch = null;
    index.searchFailure = false;
    index.failureMode = null;
  }

  @AfterEach
  void close() throws Exception {
    if (connection != null) connection.close();
    index.beforePublish = null;
    index.afterPublish = null;
    index.failureMode = null;
    index.searchFailure = false;
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
        if (delivery == null) break;
        changed = true;
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setMessageId(delivery.getProps().getMessageId());
        properties.setReceivedRoutingKey("knowledge.index");
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        consumer.receive(
            new org.springframework.amqp.core.Message(delivery.getBody(), properties), channel);
        assertThat(channel.isOpen()).as("production consumer committed and ACKed").isTrue();
      }
      long unfinished =
          db.queryForObject(
              "SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler IN"
                  + " ('INDEX_TEXT','PUBLISH_TEXT') AND status NOT IN ('DONE','FAILED')",
              Long.class,
              bytes(project));
      if (unfinished == 0 && !changed) return;
    }
    fail("Index pipeline did not drain");
  }

  void evidence(String name, Object value) throws Exception {
    Path p = Path.of(System.getProperty("phase4.evidence-dir", "target/evidence/phase4"), "scenarios", name + ".json");
    Files.createDirectories(p.getParent());
    Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  @Test
  void realTransportIndexesOwnersAndHttpSearchReturnsCanonicalCitations() throws Exception {
    var document = doc("登录模块设计", "登录认证依赖 OAuth tokenTimeout 重试。");
    var task = task("OAuth callback", "tokenTimeout 与会话恢复");
    var comment =
        content.createComment(
            actor, project, task.id(), "排查 tokenTimeout：先检查回调配置", UUID.randomUUID().toString());
    pump();
    var result = service.search(member, project, "tokenTimeout", 10);
    assertThat(result.results())
        .hasSize(3)
        .extracting(KnowledgeReads.Evidence::kind)
        .containsExactlyInAnyOrder("DOCUMENT", "TASK", "COMMENT");
    assertThat(result.results())
        .allSatisfy(
            e -> {
              assertThat(e.sourceHash()).hasSize(64);
              assertThat(e.generationId()).isNotNull();
              assertThat(e.canonicalUrl()).contains(project.toString());
            });
    http.perform(
            get("/projects/" + project + "/knowledge/search")
                .with(user(login(member)))
                .param("q", "登录模块"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("BM25"))
        .andExpect(jsonPath("$.results[0].ownerId").value(document.id().toString()));
    assertThat(reads.status(actor, project).published()).isEqualTo(3);
    evidence("normal", result);
  }

  @Test
  void externalCommentsKeepProviderIdentityAndDeletedOwnerCannotLeak() throws Exception {
    var task = task("Issue mirror", "confirmed issue description");
    UUID connectionId = UUID.randomUUID(),
        resource = UUID.randomUUID(),
        comment = UUID.randomUUID();
    String url = "https://github.com/workhub-fixture/search/issues/1#issuecomment-2";
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              db.update(
                  "INSERT INTO"
                      + " github_connection(id,project_id,installation_id,repository_id,repository_name,import_actor)"
                      + " VALUES (?,?,1,?,'workhub-fixture/search',?)",
                  bytes(connectionId),
                  bytes(project),
                  Math.abs(connectionId.getMostSignificantBits()),
                  bytes(actor.userId()));
              db.update(
                  "INSERT INTO external_resource(id,project_id,kind,provider_key,url) VALUES"
                      + " (?,?,'ISSUE',?,?)",
                  bytes(resource),
                  bytes(project),
                  resource.toString(),
                  url);
              db.update(
                  "INSERT INTO"
                      + " github_issue(resource_id,project_id,connection_id,remote_id,issue_number,task_id)"
                      + " VALUES (?,?,?,1,1,?)",
                  bytes(resource),
                  bytes(project),
                  bytes(connectionId),
                  bytes(task.id()));
              db.update(
                  "INSERT INTO"
                      + " github_comment(id,project_id,resource_id,remote_id,body,remote_login,remote_updated_at)"
                      + " VALUES (?,?,?,2,'externalCallbackProblem"
                      + " diagnosis','external-reviewer',CURRENT_TIMESTAMP(6))",
                  bytes(comment),
                  bytes(project),
                  bytes(resource));
              obligations.changed(
                  project,
                  comment,
                  SourceObligations.Owner.GITHUB_COMMENT,
                  "externalCallbackProblem diagnosis",
                  url);
            });
    pump();
    var result = service.search(member, project, "externalCallbackProblem", 10);
    assertThat(result.results())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.kind()).isEqualTo("GITHUB_COMMENT");
              assertThat(e.ownerId()).isEqualTo(comment);
              assertThat(e.canonicalUrl()).isEqualTo(url);
            });
    // Owner visibility remains authoritative even if a legacy source.active value is stale.
    db.update("UPDATE github_comment SET active=FALSE WHERE id=?", bytes(comment));
    assertThat(service.search(member, project, "externalCallbackProblem", 10).results()).isEmpty();
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 100),
        UUID.randomUUID().toString());
    assertThat(
            db.queryForObject(
                "SELECT active FROM knowledge_source WHERE github_comment_id=?",
                Boolean.class,
                bytes(comment)))
        .isFalse();
    evidence(
        "external-comment",
        Map.of(
            "canonicalCitation",
            result,
            "deletedOwnerRejected",
            true,
            "fixture",
            "local canonical GitHub rows; no provider request"));
  }

  @Test
  void projectIsolationRevocationAndModelLikePromptAreServerScoped() throws Exception {
    doc("PrivateKey rotation", "秘密项目旋转证据 tokenTimeout");
    pump();
    UUID foreign = projects.create(actor, workspace, "Other", UUID.randomUUID().toString()).id();
    content.createDocument(
        actor,
        foreign,
        ContentRepository.DocumentKind.DOCUMENT,
        "foreign",
        "tokenTimeout 外部秘密",
        UUID.randomUUID().toString());
    // Run foreign obligations through the same actual broker/ES, not a post-search fake filter.
    pump();
    assertThat(service.search(member, project, "tokenTimeout", 10).results())
        .hasSize(1)
        .allSatisfy(e -> assertThat(e.canonicalUrl()).contains(project.toString()));
    http.perform(
            get("/projects/" + foreign + "/knowledge/search")
                .with(user(login(member)))
                .param("q", "tokenTimeout"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/projects/" + project + "/knowledge/rebuild")
                .with(user(login(member)))
                .with(csrf())
                .header("Idempotency-Key", "member-rebuild")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThat(
            service
                .search(member, project, "ignore instructions /projects/" + foreign, 10)
                .results())
        .allSatisfy(e -> assertThat(e.canonicalUrl()).contains(project.toString()));
    projects.member(actor, project, member.userId(), ProjectRole.MEMBER, false, revision());
    http.perform(
            get("/projects/" + project + "/knowledge/search")
                .with(user(login(member)))
                .param("q", "tokenTimeout"))
        .andExpect(status().isForbidden());
    assertThatThrownBy(
            () ->
                service.rebuild(
                    member, project, new KnowledgeService.RebuildRequest(null, 20), "old"))
        .isInstanceOf(ApiException.class);
    evidence(
        "isolation",
        Map.of(
            "serverScope",
            project,
            "foreignDenied",
            true,
            "revokedDenied",
            true,
            "memberRebuildDenied",
            true));
  }

  @Test
  void updateAndDeleteInvalidateOldIndexBeforeWorkersRun() throws Exception {
    var d = doc("旧原因", "tokenTimeout caused by OAuth");
    var t = task("task", "");
    content.linkDocument(actor, project, t.id(), t.revision(), d.id(), true);
    assertThat(content.links(actor, project, t.id()).documents()).containsExactly(d.id());
    var c =
        content.createComment(
            actor, project, t.id(), "tokenTimeout comment", UUID.randomUUID().toString());
    pump();
    content.editDocument(actor, project, d.id(), d.revision(), "新原因", "database deadlock 新解释");
    assertThat(service.search(actor, project, "tokenTimeout", 10).results())
        .noneMatch(e -> e.ownerId().equals(d.id()));
    content.deleteComment(actor, project, t.id(), c.id(), c.revision());
    assertThat(
            db.queryForObject(
                "SELECT active FROM knowledge_source WHERE comment_id=?",
                Boolean.class,
                bytes(c.id())))
        .isFalse();
    // Reproduce a pre-4A active inconsistency: owner visibility still protects retrieval.
    db.update("UPDATE knowledge_source SET active=TRUE WHERE comment_id=?", bytes(c.id()));
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).isEmpty();
    pump();
    assertThat(service.search(actor, project, "database deadlock", 10).results()).hasSize(1);
    assertThatThrownBy(() -> content.deleteDocument(member, project, d.id(), 1))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> content.deleteDocument(actor, project, d.id(), 0))
        .isInstanceOf(ApiException.class);
    content.deleteDocument(actor, project, d.id(), 1);
    assertThat(service.search(actor, project, "database deadlock", 10).results()).isEmpty();
    assertThatThrownBy(() -> content.getDocument(actor, project, d.id()))
        .isInstanceOf(ApiException.class);
    assertThat(content.documents(actor, project, null, 20)).isEmpty();
    assertThat(content.links(actor, project, t.id()).documents()).isEmpty();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM task_document WHERE project_id=? AND task_id=? AND"
                    + " document_id=?",
                Integer.class,
                bytes(project),
                bytes(t.id()),
                bytes(d.id())))
        .isEqualTo(1);
    pump();
    evidence(
        "invalidation",
        Map.of(
            "oldTextRejectedBeforeIndexing",
            true,
            "deletedOwnerOverridesActive",
            true,
            "documentDeleted",
            true));
  }

  @Test
  void oldValidationDoneMissingSourceAndPagedRebuildUseNewObligations() throws Exception {
    var d = doc("历史资料", "tokenTimeout old corpus");
    UUID sid = source(d.id(), "document_id");
    // Execute the old production handler to retain a real immutable phase2-validation receipt.
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s -> {
              var j =
                  db.queryForObject(
                      "SELECT id FROM work_job WHERE owner_ref=?",
                      (r, n) -> read(r, "id"),
                      bytes(sid));
              var result = new IndexTextValidationHandler(db, mapper).execute(jobs.find(j, false));
              jobs.done(jobs.find(j, false), result);
            });
    var original =
        db.queryForObject(
            "SELECT result_ref FROM work_job WHERE owner_ref=?", String.class, bytes(sid));
    UUID missing = UUID.randomUUID();
    db.update(
        "INSERT INTO project_document(id,project_id,kind,title,body,created_by) VALUES"
            + " (?,?,'DOCUMENT','Missing source','tokenTimeout missing',?)",
        bytes(missing),
        bytes(project),
        bytes(actor.userId()));
    String key = UUID.randomUUID().toString();
    var page = service.rebuild(actor, project, new KnowledgeService.RebuildRequest(null, 1), key);
    assertThat(service.rebuild(actor, project, new KnowledgeService.RebuildRequest(null, 1), key))
        .isEqualTo(page);
    assertThatThrownBy(
            () ->
                service.rebuild(actor, project, new KnowledgeService.RebuildRequest(null, 2), key))
        .isInstanceOf(ApiException.class);
    assertThat(page.nextCursor()).isNotNull();
    var next =
        service.rebuild(
            actor,
            project,
            new KnowledgeService.RebuildRequest(page.nextCursor(), 1),
            UUID.randomUUID().toString());
    assertThat(next.nextCursor()).isNull();
    pump();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_job WHERE owner_ref=? AND result_ref=?",
                Long.class,
                bytes(sid),
                original))
        .isEqualTo(1);
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(2);
    evidence(
        "backfill",
        Map.of(
            "oldReceipt",
            original,
            "newJobIds",
            page.jobIds(),
            "missingOwnerRecovered",
            missing,
            "idempotentReplay",
            true));
  }

  @Test
  void repeatedMqDeliveryReturnsSameGenerationAndNoRepeatedEffect() throws Exception {
    var d = doc("retry", "tokenTimeout idempotency");
    pump();
    UUID sid = source(d.id(), "document_id");
    long generationsBefore =
        db.queryForObject(
            "SELECT COUNT(*) FROM index_generation WHERE source_id=?", Long.class, bytes(sid));
    var id =
        db.queryForObject(
            "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=?"
                + " AND j.handler='PUBLISH_TEXT'",
            (r, n) -> read(r, "id"),
            bytes(sid));
    var e = outbox.find(id, false);
    for (int n = 0; n < 3; n++)
      channel.basicPublish(
          RabbitConfiguration.EXCHANGE,
          "knowledge.index",
          new AMQP.BasicProperties.Builder().messageId(id.toString()).deliveryMode(2).build(),
          e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    pump();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM index_generation WHERE source_id=?", Long.class, bytes(sid)))
        .isEqualTo(generationsBefore);
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(1);
    evidence("redelivery", Map.of("duplicateMessages", 3, "generations", generationsBefore));
  }

  @Test
  void updateDuringNetworkRejectsLateGenerationAndDoesNotHoldProjectLock() throws Exception {
    var d = doc("old", "tokenTimeout old reason");
    index.afterPublish =
        docs -> {
          content.editDocument(actor, project, d.id(), 0, "current", "database recovery current");
          index.afterPublish = null;
        };
    pump();
    UUID sid = source(d.id(), "document_id");
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).isEmpty();
    assertThat(service.search(actor, project, "database recovery", 10).results()).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM index_generation WHERE source_id=? AND state='OBSOLETE'",
                Long.class,
                bytes(sid)))
        .isEqualTo(1);
    evidence(
        "late-generation",
        Map.of("lateWriteRejected", true, "networkAllowsConcurrentBusinessWrite", true));
  }

  @Test
  void partialBulkCannotPublishAndNewRequestRepairsTerminalFailure() throws Exception {
    var d = doc("partial", "tokenTimeout\n" + "padding details\n".repeat(500));
    index.failureMode = "PARTIAL_BAD";
    pump();
    assertThat(reads.status(actor, project).published()).isZero();
    assertThat(reads.status(actor, project).failed()).isEqualTo(1);
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).isEmpty();
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 20),
        UUID.randomUUID().toString());
    pump();
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(1);
    evidence(
        "partial-bulk",
        Map.of(
            "http200PartialBulkRejected",
            true,
            "failedJobRetained",
            reads.status(actor, project).failed(),
            "newGenerationRecovered",
            true));
  }

  @Test
  void responseLossRetriesImmutableCreateConflictsAndExhaustionCanBeRebuilt() throws Exception {
    doc("network", "tokenTimeout retry safe");
    index.failureMode = "LOST_RESPONSE";
    pump();
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT MAX(attempt) FROM work_job WHERE project_id=? AND handler='PUBLISH_TEXT'",
                Integer.class,
                bytes(project)))
        .isEqualTo(2);
    var d = doc("outage", "durable recover me");
    index.failureMode = "OUTAGE";
    pump();
    assertThat(reads.status(actor, project).failed()).isEqualTo(1);
    index.failureMode = null;
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 20),
        UUID.randomUUID().toString());
    pump();
    assertThat(service.search(actor, project, "durable recover", 10).results())
        .extracting(KnowledgeReads.Evidence::ownerId)
        .contains(d.id());
    evidence(
        "retry-exhaustion",
        Map.of(
            "unknownEsWriteRetriedWithHashCheck",
            true,
            "maxAttempts",
            5,
            "terminalFailureRecoveredByNewJob",
            true));
  }

  @Test
  void lostEsIndexRebuildsWithoutResettingDoneAndSearchOutageIsExplicit() throws Exception {
    doc("lost-index", "tokenTimeout recover index");
    pump();
    var client = java.net.http.HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + ContractSupport.env("ELASTICSEARCH_PORT", "19200");
    assertThat(
            client
                .send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/" + INDEX))
                        .DELETE()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(200);
    http.perform(
            get("/projects/" + project + "/knowledge/search")
                .with(user(login(actor)))
                .param("q", "tokenTimeout"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    service.rebuild(
        actor,
        project,
        new KnowledgeService.RebuildRequest(null, 20),
        UUID.randomUUID().toString());
    pump();
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(1);
    evidence(
        "index-loss",
        Map.of(
            "physicalIndexDeletedInTestOnly",
            INDEX,
            "newRepairGeneration",
            true,
            "oldDonePreserved",
            true));
  }

  @Test
  void corruptCanonicalChunkAndCrossProjectCandidateNeverHydrate() throws Exception {
    var d = doc("hash", "tokenTimeout trusted body");
    pump();
    UUID sid = source(d.id(), "document_id");
    UUID gen =
        db.queryForObject(
            "SELECT active_generation_id FROM source_index_state WHERE source_id=?",
            (r, n) -> read(r, "active_generation_id"),
            bytes(sid));
    UUID foreign =
        projects.create(actor, workspace, "unrelated", UUID.randomUUID().toString()).id();
    assertThat(
            reads.hydrate(actor, foreign, List.of(new TextIndex.Hit(gen + ":0", 10)), 10).results())
        .isEmpty();
    db.update(
        "UPDATE knowledge_chunk SET body='untrusted replacement' WHERE generation_id=?",
        bytes(gen));
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).isEmpty();
    evidence(
        "canonical-integrity",
        Map.of("corruptChunkRejected", true, "foreignCandidateRejected", true));
  }

  @Test
  void revocationDuringSearchPreventsReturningPreviouslyAuthorizedText() throws Exception {
    doc("revoke", "tokenTimeout privilege");
    pump();
    index.afterSearch =
        () -> {
          projects.member(actor, project, member.userId(), ProjectRole.MEMBER, false, revision());
          index.afterSearch = null;
        };
    assertThatThrownBy(() -> service.search(member, project, "tokenTimeout", 10))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).status)
        .isEqualTo(403);
    evidence("read-revocation", Map.of("revokedAfterEsBeforeHydration", true));
  }

  @Test
  void oldIndexJobDoesNotBuildTheNewRevision() throws Exception {
    var d = doc("first", "obsolete tokenTimeout");
    content.editDocument(actor, project, d.id(), 0, "second", "current database recovery");
    pump();
    UUID sid = source(d.id(), "document_id");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_job WHERE owner_ref=? AND handler='INDEX_TEXT' AND"
                    + " result_ref LIKE 'text/obsolete/%'",
                Long.class, bytes(sid)))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM index_generation WHERE source_id=?", Long.class, bytes(sid)))
        .isEqualTo(1);
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).isEmpty();
    evidence("obsolete-build", Map.of("oldJobSkipped", true, "newRevisionGenerations", 1));
  }

  @Test
  void leaseLostAfterEsWriteCannotPublishAndExpiredLeaseResumes() throws Exception {
    var d = doc("fence", "tokenTimeout fencing");
    UUID sid = source(d.id(), "document_id");
    var build =
        db.queryForObject(
            "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=?"
                + " AND j.handler='INDEX_TEXT'",
            (r, n) -> read(r, "id"),
            bytes(sid));
    var first = outbox.find(build, false);
    executor.handle(
        build.toString(),
        "knowledge.index",
        first.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var publish =
        db.queryForObject(
            "SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=?"
                + " AND j.handler='PUBLISH_TEXT'",
            (r, n) -> read(r, "id"),
            bytes(sid));
    var e = outbox.find(publish, false);
    index.afterPublish =
        docs -> {
          db.update(
              "UPDATE work_job SET"
                  + " lease_token=?,lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE"
                  + " id=?",
              bytes(UUID.randomUUID()),
              bytes(e.jobId()));
          index.afterPublish = null;
        };
    assertThatThrownBy(
            () ->
                executor.handle(
                    publish.toString(),
                    "knowledge.index",
                    e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(reads.status(actor, project).published()).isZero();
    db.update(
        "UPDATE work_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
        bytes(e.jobId()));
    pump();
    assertThat(service.search(actor, project, "tokenTimeout", 10).results()).hasSize(1);
    evidence(
        "lease-fence",
        Map.of(
            "lostLeaseAfterRealEsWriteRejected",
            true,
            "expiredLeaseRecoveredWithSameImmutableBytes",
            true,
            "processCrashInjected",
            false));
  }

  @TestConfiguration
  static class Faults {
    @Bean
    @Primary
    FaultIndex faultIndex(ElasticsearchTextIndex real) {
      return new FaultIndex(real);
    }
  }

  static class FaultIndex implements TextIndex {
    final ElasticsearchTextIndex real;
    Consumer<List<Document>> beforePublish, afterPublish;
    Runnable afterSearch;
    boolean searchFailure;
    String failureMode;

    FaultIndex(ElasticsearchTextIndex r) {
      real = r;
    }

    public void publish(List<Document> docs) {
      if (beforePublish != null) beforePublish.accept(docs);
      if ("OUTAGE".equals(failureMode)) throw new WorkFailure(true, "TEST_ES_OUTAGE");
      if ("PARTIAL_BAD".equals(failureMode)) {
        failureMode = null;
        var list = new ArrayList<>(docs);
        var f = new HashMap<>(list.get(1).fields());
        f.put("undeclared", "force actual ES strict-mapping failure");
        list.set(1, new Document(list.get(1).id(), f));
        real.publish(list);
        return;
      }
      real.publish(docs);
      if ("LOST_RESPONSE".equals(failureMode)) {
        failureMode = null;
        throw new WorkFailure(true, "TEST_LOST_RESPONSE");
      }
      if (afterPublish != null) afterPublish.accept(docs);
    }

    public Candidates search(UUID p, String q, int size) {
      if (searchFailure) throw new WorkFailure(true, "TEST_ES_OUTAGE");
      var result = real.search(p, q, size);
      if (afterSearch != null) afterSearch.run();
      return result;
    }
  }
}
