package dev.workhub.phase3;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.workhub.common.*;
import dev.workhub.contract.ContractSupport;
import dev.workhub.identity.*;
import dev.workhub.integration.github.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;

import okhttp3.mockwebserver.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "debug=false",
            "workhub.messaging.enabled=false",
            "workhub.bootstrap.login=",
            "workhub.github.dispatch-enabled=true",
            "workhub.github.fake-token=fake",
            "workhub.github.webhook-secret=test-secret"
        })
class GitHubSyncIT {
    static final ObjectMapper mapper = new ObjectMapper();
    static final MockWebServer fake = new MockWebServer();
    static final AtomicReference<ObjectNode> remote = new AtomicReference<>();
    static final AtomicLong serverRepository = new AtomicLong();
    static final AtomicInteger patches = new AtomicInteger(),
            gets = new AtomicInteger(),
            readStatus = new AtomicInteger(200);
    static volatile boolean disconnect, ignorePatch, pausePatch;
    static final AtomicReference<String> commentPage = new AtomicReference<>("[]");
    static final AtomicReference<String> evidenceBody = new AtomicReference<>("{}");
    static volatile boolean failSecondPage;

    static volatile CountDownLatch entered, release;
    static final AtomicBoolean pause = new AtomicBoolean();

    static {
        try {
            fake.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        fake.setDispatcher(
                new Dispatcher() {
                    public MockResponse dispatch(RecordedRequest r) throws InterruptedException {
                        String path = r.getPath();
                        if (path.equals("/repos/o/r"))
                            return response(
                                    readStatus.get(), "{\"id\":" + serverRepository.get() + "}");
                        if (path.contains("/comments")) return response(200, commentPage.get());
                        if (path.contains("/pulls/")
                                || path.contains("/actions/")
                                || path.contains("/commits/"))
                            return response(200, evidenceBody.get());
                        if (r.getMethod().equals("PATCH")) {
                            patches.incrementAndGet();
                            try {
                                var patch = mapper.readTree(r.getBody().readUtf8());
                                if (!ignorePatch) {
                                    var next = remote.get().deepCopy();
                                    patch.fields()
                                            .forEachRemaining(
                                                    e -> next.set(e.getKey(), e.getValue()));
                                    next.put("updated_at", "2026-09-27T01:00:01Z");
                                    remote.set(next);
                                }
                            } catch (Exception e) {
                                return response(500, "{}");
                            }
                            if (pausePatch) {
                                entered.countDown();
                                release.await(30, TimeUnit.SECONDS);
                            }
                            if (disconnect)
                                return new MockResponse()
                                        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                            return response(200, remote.get().toString());
                        }
                        gets.incrementAndGet();
                        if (path.contains("?state=") && failSecondPage) {
                            if (path.contains("page=2")) return response(403, "{}");
                            return response(
                                    200,
                                    "["
                                            + String.join(
                                                    ",",
                                                    Collections.nCopies(
                                                            100, remote.get().toString()))
                                            + "]");
                        }
                        if (path.contains("?state="))
                            return response(
                                    readStatus.get(),
                                    readStatus.get() == 200 ? "[" + remote.get() + "]" : "{}");
                        String captured = remote.get().toString();
                        if (pause.compareAndSet(true, false)) {
                            entered.countDown();
                            release.await(20, TimeUnit.SECONDS);
                        }
                        return response(
                                readStatus.get(), readStatus.get() == 200 ? captured : "{}");
                    }
                });
    }

    static MockResponse response(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "spring.datasource.url",
                () ->
                        "jdbc:mysql://127.0.0.1:"
                                + ContractSupport.env("MYSQL_PORT", "13306")
                                + "/workhub_phase3_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        p.add("workhub.github.api-base", () -> fake.url("/").toString());
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubService service;
    @Autowired GitHubStore store;
    @Autowired WorkJobExecutor executor;
    @Autowired WorkJobRepository jobs;
    @Autowired OutboxRepository outbox;
    @Autowired GitHubMaintenance maintenance;
    @Autowired PlatformTransactionManager manager;
    @Autowired TaskRepository tasks;
    @Autowired GitHubEvidence evidence;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired TaskService taskService;

    UUID project, actor, connection, resource;
    long repository;
    TransactionTemplate tx;

    @BeforeEach
    void seed() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_phase3_test");
        patches.set(0);
        gets.set(0);
        readStatus.set(200);
        disconnect = false;
        ignorePatch = false;
        pausePatch = false;
        pause.set(false);
        commentPage.set("[]");
        failSecondPage = false;
        remote.set(
                (ObjectNode)
                        mapper.readTree(
                                "{\"id\":100,\"number\":1,\"title\":\"Issue\",\"body\":\"body\",\"state\":\"open\",\"state_reason\":null,\"updated_at\":\"2026-09-27T01:00:00Z\",\"html_url\":\"https://github.com/o/r/issues/1\"}"));
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        project = UUID.randomUUID();
        actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        repository = Math.abs(UUID.randomUUID().getMostSignificantBits());
        serverRepository.set(repository);
        jdbc.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,"
                        + " 'test','unused')",
                bytes(actor),
                actor.toString());
        jdbc.update("INSERT INTO workspace(id,name) VALUES (?,'phase3')", bytes(workspace));
        jdbc.update(
                "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",
                bytes(workspace),
                bytes(actor));
        jdbc.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'phase3')",
                bytes(project),
                bytes(workspace));
        jdbc.update(
                "INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MAINTAINER')",
                bytes(project),
                bytes(actor));
        jdbc.update(
                "INSERT INTO github_installation_grant(installation_id,workspace_id) VALUES (?,?)",
                repository,
                bytes(workspace));
        jdbc.update(
                "INSERT INTO github_repository_grant(repository_id,installation_id,repository_name)"
                        + " VALUES (?,?,'o/r')",
                repository,
                repository);
        connection =
                service.connect(
                        new ActorContext(actor),
                        project,
                        new GitHubService.Connect(repository, repository, "o/r"),
                        "connect/" + UUID.randomUUID());
        // The Phase 3 protocol fixture starts after product onboarding. Remove only this
        // fixture's queued initial scan; specific tests below explicitly exercise scans.
        jdbc.update(
                "DELETE o FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE"
                        + " j.project_id=?",
                bytes(project));
        jdbc.update("DELETE FROM work_job WHERE project_id=?", bytes(project));
        jdbc.update("DELETE FROM github_operation WHERE connection_id=?", bytes(connection));
    }

    @AfterEach
    void saveScenarioEvidence(TestInfo test) throws Exception {
        if (project == null) return;
        var data = new LinkedHashMap<String, Object>();
        data.put("scenario", test.getDisplayName());
        data.put("projectId", project.toString());
        data.put("resourceId", resource == null ? null : resource.toString());
        data.put("fakeRemote", remote.get());
        data.put("patchCalls", patches.get());
        data.put("readCalls", gets.get());
        data.put(
                "jobs",
                jdbc.queryForList(
                        "SELECT HEX(id) AS id,status,attempt,result_ref FROM work_job WHERE"
                                + " project_id=? ORDER BY id",
                        bytes(project)));
        data.put(
                "activityCount",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM activity WHERE project_id=?",
                        Long.class,
                        bytes(project)));
        data.put(
                "notificationCount",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE project_id=?",
                        Long.class,
                        bytes(project)));
        if (resource != null) {
            var i = store.issue(resource, false);
            data.put("laneGeneration", i.generation());
            data.put("requestedSeq", i.requested());
            data.put("appliedSeq", i.applied());
            data.put("confirmedStatus", task().status().name());
            data.put("taskRevision", task().revision());
            var intent = store.latest(resource);
            data.put("intentState", intent == null ? null : intent.state());
        }
        var directory = java.nio.file.Path.of("target/evidence/phase3/scenarios");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(
                directory.resolve(project + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    GitHubService.Accepted webhook(String delivery) throws Exception {
        byte[] body =
                mapper.writeValueAsBytes(
                        Map.of(
                                "installation",
                                Map.of("id", repository),
                                "repository",
                                Map.of("id", repository),
                                "action",
                                "edited",
                                "issue",
                                remote.get()));
        return service.webhook(delivery, "issues", signature(body), body);
    }

    static String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    List<UUID> ready() {
        return jdbc.query(
                "SELECT id FROM work_job WHERE project_id=? AND handler='GITHUB_WORK' AND status IN"
                        + " ('WAITING','RETRY_WAIT') ORDER BY next_attempt_at,id",
                (r, n) -> uuid(r.getBytes(1)),
                bytes(project));
    }

    void execute(UUID id) {
        var j = jobs.find(id, false);
        var e = outbox.find(outbox.current(id, j.sequence()), false);
        executor.handle(
                e.id().toString(), e.destination(), e.wire().getBytes(StandardCharsets.UTF_8));
    }

    void due() {
        jdbc.update(
                "UPDATE work_job SET next_attempt_at=TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)) WHERE"
                        + " project_id=? AND status IN ('WAITING','RETRY_WAIT')",
                bytes(project));
        jdbc.update(
                "UPDATE outbox_message SET available_at=TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6))"
                        + " WHERE job_id IN (SELECT id FROM work_job WHERE project_id=?)",
                bytes(project));
        // Deterministically advance the installation's delay as well as message/job due times.
        // Tests of rate admission assert the blocked step before calling this helper.
        jdbc.update(
                "UPDATE github_installation_lane SET"
                        + " next_allowed_at=TIMESTAMPADD(SECOND,-5,UTC_TIMESTAMP(6)) WHERE"
                        + " installation_id=? AND owner_job IS NULL",
                repository);
    }

    void drain() {
        for (int n = 0; n < 20; n++) {
            due();
            var ids = ready();
            if (ids.isEmpty()) return;
            ids.forEach(this::execute);
        }
        throw new AssertionError("did not drain");
    }

    void importIssue() throws Exception {
        webhook(UUID.randomUUID().toString());
        drain();
        resource =
                jdbc.queryForObject(
                        "SELECT resource_id FROM github_issue WHERE connection_id=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(connection));
    }

    Task task() {
        var i = store.issue(resource, false);
        return tasks.find(project, i.task()).orElseThrow();
    }

    GitHubStore.Intent closeIntent() throws Exception {
        var i = store.issue(resource, false);
        return service.intent(
                new ActorContext(actor),
                project,
                i.task(),
                new GitHubService.IntentRequest(
                        task().revision(),
                        i.syncRevision(),
                        mapper.readTree("{\"state\":\"closed\",\"state_reason\":\"completed\"}")),
                "intent/" + UUID.randomUUID());
    }

    @Test
    void f04DuplicateHundredAndSameFactDifferentDeliveries() throws Exception {
        String delivery = UUID.randomUUID().toString();
        assertThat(webhook(delivery).status()).isEqualTo("ACCEPTED");
        for (int n = 0; n < 100; n++) assertThat(webhook(delivery).status()).isEqualTo("DUPLICATE");
        drain();
        resource =
                jdbc.queryForObject(
                        "SELECT resource_id FROM github_issue WHERE connection_id=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(connection));
        long revision = task().revision();
        long activity =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM activity WHERE project_id=?",
                        Long.class,
                        bytes(project));
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(task().revision()).isEqualTo(revision);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM activity WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isEqualTo(activity);
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
        assertThat(patches.get()).isZero();
    }

    @Test
    void hashConflictIsAuditedAndCannotReroute() throws Exception {
        String id = UUID.randomUUID().toString();
        webhook(id);
        remote.get().put("title", "different");
        assertThat(webhook(id).status()).isEqualTo("HASH_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT hash_conflict FROM webhook_inbox WHERE delivery_id=?",
                                Boolean.class,
                                id))
                .isTrue();
        assertThat(ready()).hasSize(1);
    }

    @Test
    void pendingDoesNotChangeConfirmedAndPatchIsReadBack() throws Exception {
        importIssue();
        var in = closeIntent();
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.DONE);
        assertThat(store.intent(in.id()).state()).isEqualTo("APPLIED");
        assertThat(patches.get()).isEqualTo(1);
        webhook(UUID.randomUUID().toString());
        long rev = task().revision();
        drain();
        assertThat(task().revision()).isEqualTo(rev);
    }

    @RepeatedTest(3)
    void f08RemoteAppliedDisconnectedResponseIsVerifiedOnce() throws Exception {
        importIssue();
        disconnect = true;
        var in = closeIntent();
        drain();
        assertThat(store.intent(in.id()).state()).isEqualTo("APPLIED");
        assertThat(task().status()).isEqualTo(TaskStatus.DONE);
        assertThat(patches.get()).isEqualTo(1);
    }

    @Test
    void f09PrecheckConflictDoesNotPatch() throws Exception {
        importIssue();
        var in = closeIntent();
        remote.get().put("title", "Bob edited").put("updated_at", "2026-09-27T01:00:02Z");
        drain();
        assertThat(patches.get()).isZero();
        assertThat(store.intent(in.id()).state()).isEqualTo("CONFLICT");
        assertThat(task().title()).isEqualTo("Bob edited");
    }

    @Test
    void f09IgnoredPatchDoesNotManufactureSuccess() throws Exception {
        importIssue();
        ignorePatch = true;
        var in = closeIntent();
        execute(ready().get(0));
        jdbc.update(
                "UPDATE sync_intent SET send_started_at=TIMESTAMPADD(SECOND,-121,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(in.id()));
        drain();
        assertThat(store.intent(in.id()).state()).isEqualTo("ERROR");
        assertThat(patches.get()).isEqualTo(1);
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
    }

    @Test
    void recoveryAfterSendStartedOnlyVerifies() throws Exception {
        importIssue();
        var in = closeIntent();
        UUID dispatch = ready().get(0);
        jdbc.update(
                "UPDATE sync_intent SET state='SEND_STARTED',send_started_at=UTC_TIMESTAMP(6) WHERE"
                        + " id=?",
                bytes(in.id()));
        jdbc.update(
                "UPDATE work_job SET"
                    + " status='RUNNING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))"
                    + " WHERE id=?",
                bytes(UUID.randomUUID()),
                bytes(dispatch));
        remote.get().put("state", "closed").put("state_reason", "completed");
        maintenance.recoverExpired();
        drain();
        assertThat(patches.get()).isZero();
        assertThat(store.intent(in.id()).state()).isEqualTo("APPLIED");
        assertThat(task().status()).isEqualTo(TaskStatus.DONE);
    }

    @RepeatedTest(3)
    void f05OldWorkerCannotOverwriteNewLane() throws Exception {
        importIssue();
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.refresh(resource);
                });
        UUID old = ready().get(0);
        // This test starts at the remote fencing window, not at early-delivery admission.
        // Advance both job/message deadlines and the unowned installation lane explicitly.
        due();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        pause.set(true);
        var pool = Executors.newSingleThreadExecutor();
        Future<?> pending = pool.submit(() -> execute(old));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "UPDATE work_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                            + " id=?",
                    bytes(old));
            jdbc.update(
                    "UPDATE external_resource SET"
                            + " lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
                    bytes(resource));
            remote.get()
                    .put("state", "closed")
                    .put("state_reason", "completed")
                    .put("updated_at", "2026-09-27T01:00:02Z");
            maintenance.recoverExpired();
            drain();
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertThat(task().status()).isEqualTo(TaskStatus.DONE);
            assertThat(task().revision()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void f05SignalDuringGetRequiresFreshRead() throws Exception {
        importIssue();
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.refresh(resource);
                });
        UUID old = ready().get(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        pause.set(true);
        var pool = Executors.newSingleThreadExecutor();
        Future<?> pending = pool.submit(() -> execute(old));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            remote.get().put("title", "new title");
            tx.executeWithoutResult(
                    s -> {
                        store.lockProject(project);
                        store.refresh(resource);
                    });
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertThat(task().title()).isEqualTo("Issue");
            drain();
            assertThat(task().title()).isEqualTo("new title");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void f10UnavailablePreservesTaskAndReconcileRecovers() throws Exception {
        importIssue();
        readStatus.set(404);
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(task().title()).isEqualTo("Issue");
        assertThat(store.issue(resource, false).availability()).isEqualTo("UNAVAILABLE");
        readStatus.set(200);
        remote.get().put("title", "missed webhook");
        service.reconcile(new ActorContext(actor), project, connection);
        drain();
        assertThat(task().title()).isEqualTo("missed webhook");
        assertThat(store.issue(resource, false).availability()).isEqualTo("AVAILABLE");
        assertThat(store.connection(connection).watermark()).isNotNull();
    }

    @Test
    void scanFailureDoesNotAdvanceWatermark() throws Exception {
        readStatus.set(403);
        service.reconcile(new ActorContext(actor), project, connection);
        drain();
        assertThat(store.connection(connection).watermark()).isNull();
    }

    @Test
    void revokedActorStopsQueuedWrite() throws Exception {
        importIssue();
        var in = closeIntent();
        jdbc.update(
                "UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",
                bytes(project),
                bytes(actor));
        drain();
        assertThat(patches.get()).isZero();
        assertThat(store.intent(in.id()).state()).isEqualTo("ERROR");
    }

    @Test
    void reassignedMemberCannotSendPreviouslyQueuedIntent() throws Exception {
        importIssue();
        UUID member = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,"
                        + " 'member','unused')",
                bytes(member),
                member.toString());
        jdbc.update(
                "INSERT INTO workspace_member(workspace_id,user_id) SELECT workspace_id,? FROM"
                        + " project WHERE id=?",
                bytes(member),
                bytes(project));
        jdbc.update(
                "INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MEMBER')",
                bytes(project),
                bytes(member));
        var accept = new TaskRequests.Patch();
        accept.setAssigneeId(member);
        taskService.acceptTriage(
                new ActorContext(actor), project, task().id(), task().revision(), accept);
        var issue = store.issue(resource, false);
        var intent =
                service.intent(
                        new ActorContext(member),
                        project,
                        task().id(),
                        new GitHubService.IntentRequest(
                                task().revision(),
                                issue.syncRevision(),
                                mapper.readTree("{\"body\":\"A proposed description\"}")),
                        UUID.randomUUID().toString());
        // Keep the revision unchanged to isolate the authorization check from conflict detection.
        jdbc.update("UPDATE task SET assignee_id=NULL WHERE id=?", bytes(task().id()));
        drain();
        assertThat(patches.get()).isZero();
        assertThat(store.intent(intent.id()).state()).isEqualTo("ERROR");
    }

    @Test
    void archivedQueuedIntentNeverSendsAndReadbackAfterSendStillConfirms() throws Exception {
        importIssue();
        var first = closeIntent();
        jdbc.update("UPDATE project SET archived=TRUE WHERE id=?", bytes(project));
        drain();
        assertThat(patches.get()).isZero();
        assertThat(store.intent(first.id()).state()).isEqualTo("ERROR");
        jdbc.update("UPDATE project SET archived=FALSE WHERE id=?", bytes(project));
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.refresh(resource);
                });
        drain();
        var sent = closeIntent();
        execute(ready().get(0));
        assertThat(patches.get()).isEqualTo(1);
        jdbc.update("UPDATE project SET archived=TRUE WHERE id=?", bytes(project));
        jdbc.update(
                "UPDATE workspace_member m JOIN project p ON p.workspace_id=m.workspace_id SET"
                        + " m.active=FALSE WHERE p.id=? AND m.user_id=?",
                bytes(project),
                bytes(actor));
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.DONE);
        assertThat(store.intent(sent.id()).state()).isEqualTo("APPLIED");
        assertThat(patches.get()).isEqualTo(1);
    }

    @Test
    void guessedRepositoryOutsideDeploymentGrantIsForbidden() {
        assertThatThrownBy(
                        () ->
                                service.connect(
                                        new ActorContext(actor),
                                        project,
                                        new GitHubService.Connect(
                                                repository, repository + 1, "other/repository"),
                                        UUID.randomUUID().toString()))
                .isInstanceOf(dev.workhub.common.ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM github_connection WHERE project_id=?",
                                Integer.class,
                                bytes(project)))
                .isEqualTo(1);
    }

    @Test
    void userScanOperationCompletesOnlyAfterCommittedMirrors() throws Exception {
        UUID operation = service.reconcile(new ActorContext(actor), project, connection);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM github_operation WHERE id=?",
                                String.class,
                                bytes(operation)))
                .isEqualTo("QUEUED");
        drain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM github_operation WHERE id=?",
                                String.class,
                                bytes(operation)))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM github_operation_resource x JOIN github_issue"
                                    + " i ON i.resource_id=x.resource_id WHERE x.operation_id=? AND"
                                    + " i.task_id IS NOT NULL",
                                Integer.class,
                                bytes(operation)))
                .isEqualTo(1);
    }

    @Test
    void unsupportedStateKeepsLastExplainableFact() throws Exception {
        importIssue();
        remote.get().put("state", "closed").put("state_reason", "future");
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
        assertThat(store.issue(resource, false).error())
                .isEqualTo("ERROR_UNSUPPORTED_REMOTE_STATE");
    }

    @RepeatedTest(3)
    void f08ActualProcessExitAfterRemotePatch() throws Exception {
        importIssue();
        var in = closeIntent();
        UUID job = ready().get(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        pausePatch = true;
        var directory = java.nio.file.Path.of("target/evidence/phase3/processes");
        java.nio.file.Files.createDirectories(directory);
        var log = directory.resolve("send-started-" + UUID.randomUUID() + ".log");
        String url;
        try (var db = dataSource.getConnection()) {
            url = db.getMetaData().getURL();
        }
        Process process =
                new ProcessBuilder(
                                java.nio.file.Path.of(System.getProperty("java.home"), "bin/java")
                                        .toString(),
                                "-Xmx192m",
                                "-XX:ActiveProcessorCount=2",
                                "-cp",
                                System.getProperty("java.class.path"),
                                GitHubCrashWorker.class.getName(),
                                url,
                                fake.url("/").toString(),
                                job.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertThat(entered.await(35, TimeUnit.SECONDS))
                    .as("PATCH checkpoint, child log %s", log)
                    .isTrue();
            assertThat(store.intent(in.id()).state()).isEqualTo("SEND_STARTED");
            assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            pausePatch = false;
            jdbc.update(
                    "UPDATE work_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                            + " id=?",
                    bytes(job));
            jdbc.update(
                    "UPDATE external_resource SET"
                            + " lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
                    bytes(resource));
            jdbc.update(
                    "UPDATE github_installation_lane SET"
                            + " lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                            + " installation_id=?",
                    repository);
            maintenance.recoverExpired();
            drain();
            assertThat(patches.get()).isEqualTo(1);
            assertThat(task().status()).isEqualTo(TaskStatus.DONE);
            assertThat(store.intent(in.id()).state()).isEqualTo("APPLIED");
            System.out.println(
                    "PHASE3_PROCESS exit="
                            + process.exitValue()
                            + " patches="
                            + patches.get()
                            + " intent="
                            + store.intent(in.id()).state()
                            + " log="
                            + log);
        } finally {
            release.countDown();
            pausePatch = false;
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void f08ApplyRollbackAfterPatchCannotResend() throws Exception {
        importIssue();
        var in = closeIntent();
        execute(ready().get(0));
        UUID verify = ready().get(0);
        due();
        jdbc.execute(
                "ALTER TABLE activity ADD CONSTRAINT phase3_reject_observed CHECK (cause_id<>X'"
                        + HexFormat.of().formatHex(bytes(verify))
                        + "')");
        try {
            due();
            assertThatThrownBy(() -> execute(verify)).isInstanceOf(RuntimeException.class);
            assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
        } finally {
            jdbc.execute("ALTER TABLE activity DROP CHECK phase3_reject_observed");
        }
        jdbc.update(
                "UPDATE work_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                        + " id=?",
                bytes(verify));
        jdbc.update(
                "UPDATE external_resource SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(resource));
        jdbc.update(
                "UPDATE github_installation_lane SET"
                        + " lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                        + " installation_id=?",
                repository);
        maintenance.recoverExpired();
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.DONE);
        assertThat(patches.get()).isEqualTo(1);
    }

    @Test
    void f09BobReopensAfterUncertainPatch() throws Exception {
        importIssue();
        disconnect = true;
        var in = closeIntent();
        execute(ready().get(0));
        remote.get()
                .put("state", "open")
                .putNull("state_reason")
                .put("updated_at", "2026-09-27T01:00:03Z");
        drain();
        assertThat(store.intent(in.id()).state()).isEqualTo("CONFLICT");
        assertThat(patches.get()).isEqualTo(1);
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
    }

    @Test
    void f10FailedSecondPageDoesNotAdvanceWatermark() throws Exception {
        failSecondPage = true;
        service.reconcile(new ActorContext(actor), project, connection);
        drain();
        assertThat(store.connection(connection).watermark()).isNull();
    }

    @Test
    void commentsAreExternalSourcesAndFullEnumerationTombstones() throws Exception {
        commentPage.set(
                "[{\"id\":22,\"body\":\"external"
                    + " text\",\"user\":{\"login\":\"external\"},\"updated_at\":\"2026-09-27T01:00:00Z\"}]");
        importIssue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_source WHERE project_id=? AND"
                                        + " github_comment_id IS NOT NULL AND active",
                                Long.class,
                                bytes(project)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isZero();
        commentPage.set("[]");
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.job(
                            project,
                            resource,
                            "comment-test/" + UUID.randomUUID(),
                            Map.of(
                                    "stage",
                                    "COMMENTS",
                                    "page",
                                    1,
                                    "round",
                                    UUID.randomUUID(),
                                    "initial",
                                    false),
                            store.now());
                });
        drain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM github_comment WHERE resource_id=?",
                                Boolean.class,
                                bytes(resource)))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM knowledge_source WHERE project_id=? AND"
                                        + " github_comment_id IS NOT NULL",
                                Boolean.class,
                                bytes(project)))
                .isFalse();
    }

    @Test
    void f13EvidenceDoesNotChangeTaskStatusAndPinsCiIdentity() throws Exception {
        importIssue();
        String sha = "a".repeat(40);
        evidenceBody.set("{\"id\":33,\"number\":2,\"state\":\"closed\",\"merged\":true}");
        UUID pr =
                evidence.link(
                        new ActorContext(actor),
                        project,
                        task().id(),
                        new GitHubEvidence.Link(connection, task().revision(), "PR", 2, null, null),
                        "pr/" + UUID.randomUUID());
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT availability FROM external_resource WHERE id=?",
                                String.class,
                                bytes(pr)))
                .isEqualTo("AVAILABLE");
        evidenceBody.set(
                "{\"id\":44,\"run_attempt\":2,\"head_sha\":\""
                        + sha
                        + "\",\"conclusion\":\"failure\"}");
        UUID ci =
                evidence.link(
                        new ActorContext(actor),
                        project,
                        task().id(),
                        new GitHubEvidence.Link(
                                connection, task().revision(), "CI_RUN", 44, 1, sha),
                        "ci/" + UUID.randomUUID());
        drain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT availability FROM external_resource WHERE id=?",
                                String.class,
                                bytes(ci)))
                .isEqualTo("UNAVAILABLE");
        assertThat(task().status()).isEqualTo(TaskStatus.TRIAGE);
    }

    @Test
    void appPingWithoutRepositoryRequiresSignatureAndNeverRoutesWork() throws Exception {
        byte[] body =
                mapper.writeValueAsBytes(Map.of("zen", "Keep it logically awesome.", "hook_id", 1));
        for (int n = 0; n < 2; n++)
            http.perform(
                            post("/integrations/github/webhook")
                                    .contentType("application/json")
                                    .content(body)
                                    .header("X-GitHub-Delivery", "app-ping")
                                    .header("X-GitHub-Event", "ping")
                                    .header("X-Hub-Signature-256", signature(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PING"));
        http.perform(
                        post("/integrations/github/webhook")
                                .contentType("application/json")
                                .content(body)
                                .header("X-GitHub-Delivery", "app-ping")
                                .header("X-GitHub-Event", "ping"))
                .andExpect(status().isUnauthorized());
        // The special acknowledgement must not bypass repository checks for domain events.
        http.perform(
                        post("/integrations/github/webhook")
                                .contentType("application/json")
                                .content(body)
                                .header("X-GitHub-Delivery", "app-ping")
                                .header("X-GitHub-Event", "issues")
                                .header("X-Hub-Signature-256", signature(body)))
                .andExpect(status().isForbidden());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM webhook_inbox WHERE connection_id=?",
                                Integer.class,
                                bytes(connection)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_job WHERE project_id=?",
                                Integer.class,
                                bytes(project)))
                .isZero();
    }

    @Test
    void signedNonObjectWebhookIsRejected() throws Exception {
        for (String value : List.of("null", "[]", "123")) {
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            http.perform(
                            post("/integrations/github/webhook")
                                    .contentType("application/json")
                                    .content(body)
                                    .header("X-GitHub-Delivery", UUID.randomUUID())
                                    .header("X-GitHub-Event", "ping")
                                    .header("X-Hub-Signature-256", signature(body)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void httpWebhookCsrfExceptionAndIntentAuthorization() throws Exception {
        byte[] body =
                mapper.writeValueAsBytes(
                        Map.of(
                                "installation",
                                Map.of("id", repository),
                                "repository",
                                Map.of("id", repository),
                                "issue",
                                remote.get()));
        http.perform(
                        post("/integrations/github/webhook")
                                .contentType("application/json")
                                .content(body)
                                .header("X-GitHub-Delivery", UUID.randomUUID())
                                .header("X-GitHub-Event", "issues")
                                .header("X-Hub-Signature-256", signature(body)))
                .andExpect(status().isAccepted());
        http.perform(
                        post("/integrations/github/webhook")
                                .contentType("application/json")
                                .content(body)
                                .header("X-GitHub-Delivery", UUID.randomUUID())
                                .header("X-GitHub-Event", "issues"))
                .andExpect(status().isUnauthorized());
        drain();
        resource =
                jdbc.queryForObject(
                        "SELECT resource_id FROM github_issue WHERE connection_id=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(connection));
        String path = "/projects/" + project + "/tasks/" + task().id() + "/github/intents";
        String request =
                mapper.writeValueAsString(
                        Map.of(
                                "expectedTaskRevision",
                                task().revision(),
                                "expectedSyncRevision",
                                store.issue(resource, false).syncRevision(),
                                "desired",
                                Map.of("title", "new")));
        http.perform(
                        post(path)
                                .with(user(actor.toString()))
                                .contentType("application/json")
                                .content(request)
                                .header("Idempotency-Key", "api-intent"))
                .andExpect(status().isForbidden());
        http.perform(
                        post(path)
                                .with(user(actor.toString()))
                                .with(csrf())
                                .contentType("application/json")
                                .content(request)
                                .header("Idempotency-Key", "api-intent"))
                .andExpect(status().isAccepted());
    }

    @Test
    void inboxAndIntentRollbackTheirWorkObligations() throws Exception {
        long before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_job WHERE project_id=?",
                        Long.class,
                        bytes(project));
        tx.executeWithoutResult(
                s -> {
                    try {
                        webhook("rollback");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    s.setRollbackOnly();
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_job WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM webhook_inbox WHERE connection_id=?",
                                Long.class,
                                bytes(connection)))
                .isZero();
        importIssue();
        tx.executeWithoutResult(
                s -> {
                    try {
                        closeIntent();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    s.setRollbackOnly();
                });
        assertThat(store.latest(resource)).isNull();
        assertThat(ready()).isEmpty();
    }

    @Test
    void realRabbitDeliveryAcknowledgesAfterDurableRoute() throws Exception {
        webhook(UUID.randomUUID().toString());
        UUID job = ready().get(0);
        var j = jobs.find(job, false);
        var e = outbox.find(outbox.current(job, j.sequence()), false);
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(Integer.parseInt(ContractSupport.env("RABBITMQ_PORT", "15672")));
        factory.setUsername(ContractSupport.env("RABBITMQ_USER", "workhub"));
        factory.setPassword(ContractSupport.required("RABBITMQ_PASSWORD"));
        factory.setVirtualHost("phase3_test");
        try (var conn = factory.newConnection();
                var channel = conn.createChannel()) {
            channel.exchangeDeclare(RabbitConfiguration.EXCHANGE, "direct", true);
            channel.queueDeclare("github.work", true, false, false, null);
            channel.queueBind("github.work", RabbitConfiguration.EXCHANGE, "github.work");
            channel.queuePurge("github.work");
            channel.confirmSelect();
            channel.basicPublish(
                    RabbitConfiguration.EXCHANGE,
                    "github.work",
                    true,
                    new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .messageId(e.id().toString())
                            .deliveryMode(2)
                            .build(),
                    e.wire().getBytes());
            channel.waitForConfirmsOrDie(3000);
            var delivery = channel.basicGet("github.work", false);
            assertThat(delivery).isNotNull();
            var props = new org.springframework.amqp.core.MessageProperties();
            props.setMessageId(e.id().toString());
            props.setReceivedRoutingKey("github.work");
            props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
            new WorkJobConsumer(executor)
                    .receive(
                            new org.springframework.amqp.core.Message(delivery.getBody(), props),
                            channel);
            assertThat(jobs.find(job, false).status()).isEqualTo("DONE");
            assertThat(outbox.find(e.id(), false).disposition()).isEqualTo("SUCCEEDED");
            assertThat(channel.basicGet("github.work", true)).isNull();
        }
    }

    @Test
    void idempotentIntentReplayAndActiveIntentConflict() throws Exception {
        importIssue();
        var i = store.issue(resource, false);
        var request =
                new GitHubService.IntentRequest(
                        task().revision(),
                        i.syncRevision(),
                        mapper.readTree("{\"title\":\"edited\"}"));
        var first =
                service.intent(new ActorContext(actor), project, task().id(), request, "same-key");
        assertThat(
                        service.intent(
                                        new ActorContext(actor),
                                        project,
                                        task().id(),
                                        request,
                                        "same-key")
                                .id())
                .isEqualTo(first.id());
        var second =
                new GitHubService.IntentRequest(
                        task().revision(),
                        store.issue(resource, false).syncRevision(),
                        request.desired());
        assertThatThrownBy(
                        () ->
                                service.intent(
                                        new ActorContext(actor),
                                        project,
                                        task().id(),
                                        second,
                                        "other-key"))
                .isInstanceOf(ApiException.class);
        drain();
        assertThat(patches.get()).isEqualTo(1);
        assertThat(
                        service.intent(
                                        new ActorContext(actor),
                                        project,
                                        task().id(),
                                        request,
                                        "same-key")
                                .state())
                .isEqualTo("APPLIED");
    }

    @Test
    void persistentRateGateDefersWithoutUsingExecutionBudget() throws Exception {
        importIssue();
        int before = gets.get();
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.refresh(resource);
                });
        jdbc.update(
                "UPDATE github_installation_lane SET"
                        + " next_allowed_at=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE"
                        + " installation_id=?",
                repository);
        UUID id = ready().get(0);
        execute(id);
        assertThat(gets.get()).isEqualTo(before);
        assertThat(jobs.find(id, false).status()).isEqualTo("RETRY_WAIT");
        assertThat(jobs.find(id, false).attempt()).isZero();
        jdbc.update(
                "UPDATE github_installation_lane SET next_allowed_at=UTC_TIMESTAMP(6) WHERE"
                        + " installation_id=?",
                repository);
        drain();
    }

    @Test
    void repositoryIdentityMismatchDoesNotImportOrMoveWatermark() throws Exception {
        serverRepository.set(repository + 1);
        service.reconcile(new ActorContext(actor), project, connection);
        drain();
        assertThat(store.connection(connection).watermark()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM github_issue WHERE connection_id=?",
                                Long.class,
                                bytes(connection)))
                .isZero();
    }

    @Test
    void errorEpisodeNotifiesOnceAndLateObservationResolvesUnknown() throws Exception {
        importIssue();
        ignorePatch = true;
        var in = closeIntent();
        execute(ready().get(0));
        jdbc.update(
                "UPDATE sync_intent SET send_started_at=TIMESTAMPADD(SECOND,-121,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(in.id()));
        drain();
        jdbc.update(
                "UPDATE github_issue SET error_since=TIMESTAMPADD(SECOND,-301,UTC_TIMESTAMP(6))"
                        + " WHERE resource_id=?",
                bytes(resource));
        maintenance.notifyErrors();
        maintenance.notifyErrors();
        assertThatThrownBy(this::closeIntent)
                .isInstanceOf(dev.workhub.common.ApiException.class)
                .hasMessageContaining("观察确认");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isEqualTo(1);
        remote.get().put("state", "closed").put("state_reason", "completed");
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(store.intent(in.id()).state()).isEqualTo("APPLIED");
        assertThat(store.issue(resource, false).error()).isNull();
        assertThat(patches.get()).isEqualTo(1);
    }

    @Test
    void confirmedCloseOnlyUnblocksAfterReadbackAndCancellationDoesNotUnblock() throws Exception {
        importIssue();
        UUID downstream = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " task(id,project_id,title,description,status,type,origin,created_by,assignee_id)"
                    + " VALUES (?,?,'downstream','','TODO','TASK','MANUAL',?,?)",
                bytes(downstream),
                bytes(project),
                bytes(actor),
                bytes(actor));
        jdbc.update(
                "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES"
                        + " (?,?,?,?)",
                bytes(project),
                bytes(task().id()),
                bytes(downstream),
                bytes(actor));
        var in = closeIntent();
        assertThat(tasks.find(project, downstream).orElseThrow().isBlocked()).isTrue();
        execute(ready().get(0));
        assertThat(tasks.find(project, downstream).orElseThrow().isBlocked()).isTrue();
        drain();
        assertThat(tasks.find(project, downstream).orElseThrow().isBlocked()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isEqualTo(1);
        remote.get().put("state_reason", "not_planned").put("updated_at", "2026-09-27T01:00:03Z");
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(task().status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(tasks.find(project, downstream).orElseThrow().isBlocked()).isTrue();
    }

    @Test
    void remoteWorkFailureNeverUsesLocalExecutionRetry() throws Exception {
        importIssue();
        tx.executeWithoutResult(
                s -> {
                    store.lockProject(project);
                    store.refresh(resource);
                });
        UUID id = ready().get(0);
        RemoteWorkHandler remoteFailure =
                new RemoteWorkHandler() {
                    public String kind() {
                        return "GITHUB_WORK";
                    }

                    public void lockBusiness(WorkJobRepository.Job j) {
                        store.lockProject(j.project());
                    }

                    public Step prepare(WorkJobRepository.Job j) {
                        return Step.call("request");
                    }

                    public Object perform(Object request) {
                        throw new WorkFailure(true, "REMOTE_UNKNOWN");
                    }

                    public Step complete(WorkJobRepository.Job j, Object q, Object r) {
                        throw new AssertionError();
                    }
                };
        var isolated =
                new WorkJobExecutor(
                        jobs,
                        outbox,
                        new FailureRepository(jdbc, outbox, jobs, new Json(mapper)),
                        new EnvelopeValidator(mapper),
                        new HandlerRegistry(List.of(remoteFailure)),
                        new RetryPolicy(),
                        manager);
        var j = jobs.find(id, false);
        var e = outbox.find(outbox.current(id, j.sequence()), false);
        assertThatThrownBy(
                        () ->
                                isolated.handle(
                                        e.id().toString(), e.destination(), e.wire().getBytes()))
                .isInstanceOf(WorkFailure.class);
        assertThat(jobs.find(id, false).status()).isEqualTo("RUNNING");
        assertThat(outbox.find(e.id(), false).disposition()).isEqualTo("UNHANDLED");
        jdbc.update(
                "UPDATE work_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
                        + " id=?",
                bytes(id));
        maintenance.recoverExpired();
        drain();
    }

    @Test
    void malformedIssueTimeIsVisibleErrorWithoutOverwritingConfirmed() throws Exception {
        importIssue();
        remote.get().put("updated_at", "invalid-time");
        webhook(UUID.randomUUID().toString());
        drain();
        assertThat(task().title()).isEqualTo("Issue");
        assertThat(store.issue(resource, false).error())
                .isEqualTo("ERROR_UNSUPPORTED_REMOTE_STATE");
    }
}
