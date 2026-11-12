package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.common.ApiException;
import dev.workhub.identity.ActorContext;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "workhub.messaging.enabled=false",
            "workhub.retrieval.enabled=false",
            "workhub.embedding.enabled=false",
            "workhub.agent.enabled=false",
            "workhub.github.enabled=false",
            "workhub.model-admission.enabled=false",
            "workhub.bootstrap.login=",
            "server.servlet.session.cookie.secure=false",
            "debug=false",
            "logging.level.root=WARN",
            "workhub.mail.key-file=target/overview-realtime/mail.key"
        })
class ProjectOverviewRealtimeIT {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "spring.datasource.url",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "OVERVIEW_REALTIME_JDBC_URL",
                                        "jdbc:mysql://127.0.0.1:28306/workhub_overview_realtime_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        p.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("OVERVIEW_DB_USER", "team_model_fixture"));
        p.add(
                "spring.datasource.password",
                () ->
                        System.getenv()
                                .getOrDefault("OVERVIEW_DB_PASSWORD", "team-model-local-only"));
    }

    @Autowired JdbcTemplate db;
    @Autowired GraphReadService graph;
    @Autowired GraphSyncService sync;
    @Autowired OverviewState states;
    @Autowired TaskPreviewService previews;
    @Autowired JournalMaintenance maintenance;
    @Autowired TaskService tasks;
    @Autowired ContentService content;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired ProjectGraphStreams streams;
    @LocalServerPort int port;
    UUID user, member, workspace, project;
    ActorContext actor;

    @BeforeEach
    void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("workhub_overview_realtime_test");
        user = UUID.randomUUID();
        member = UUID.randomUUID();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        actor = new ActorContext(user);
        for (UUID u : List.of(user, member))
            db.update(
                    "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,?)",
                    bytes(u),
                    "realtime-" + u,
                    u.equals(user) ? "维护者" : "成员",
                    passwords.encode("Realtime-fixture-2026!"));
        db.update("INSERT INTO workspace(id,name) VALUES (?,'Realtime fixture')", bytes(workspace));
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES"
                        + " (?,?,'ADMIN'),(?,?,'MEMBER')",
                bytes(workspace),
                bytes(user),
                bytes(workspace),
                bytes(member));
        db.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Realtime project')",
                bytes(project),
                bytes(workspace));
        db.update(
                "INSERT INTO project_member(project_id,user_id,role) VALUES"
                        + " (?,?,'MAINTAINER'),(?,?,'MEMBER')",
                bytes(project),
                bytes(user),
                bytes(project),
                bytes(member));
    }

    UUID task(String title, String status) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " task(id,project_id,title,description,status,type,origin,priority,assignee_id,created_by)"
                    + " VALUES (?,?,?,'scope',?,'TASK','MANUAL',1,?,?)",
                bytes(id),
                bytes(project),
                title,
                status,
                bytes(user),
                bytes(user));
        return id;
    }

    void edge(UUID from, UUID to) {
        db.update(
                "INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES"
                        + " (?,?,?,?)",
                bytes(project),
                bytes(from),
                bytes(to),
                bytes(user));
    }

    void status(UUID id, String status) {
        db.update("UPDATE task SET status=?,revision=revision+1 WHERE id=?", status, bytes(id));
    }

    GraphSyncService.Delta delta(String after, UUID... ids) {
        return sync.sync(actor, project, new GraphSyncService.Request(after, List.of(ids), null));
    }

    @Test
    void countersAreCanonicalAcrossEveryTaskStateAndDependencyChange() {
        UUID a = task("a", "TRIAGE"), b = task("b", "TODO"), c = task("c", "DONE");
        edge(a, b);
        edge(b, c);
        for (String status : List.of("TODO", "IN_PROGRESS", "DONE", "CANCELED", "TRIAGE")) {
            status(a, status);
            assertThat(maintenance.reconcile(project, false)).isTrue();
        }
        db.update(
                "DELETE FROM task_dependency WHERE project_id=? AND blocker_id=?",
                bytes(project),
                bytes(b));
        var state = states.state(actor, project);
        assertThat(state.totalTasks()).isEqualTo(3);
        assertThat(state.totalEdges()).isEqualTo(1);
        assertThat(state.counts().get("TRIAGE")).isEqualTo(1);
        assertThat(maintenance.reconcile(project, false)).isTrue();
    }

    @Test
    void rollbackIdempotentReplayAndNoopLeaveSequenceAndCountersUnchanged() {
        UUID a = task("a", "TODO");
        var before = states.state(actor, project);
        tx.executeWithoutResult(
                t -> {
                    status(a, "DONE");
                    task("rolled back", "TRIAGE");
                    t.setRollbackOnly();
                });
        assertThat(states.state(actor, project)).isEqualTo(before);
        db.update("UPDATE task SET status=status,description=description WHERE id=?", bytes(a));
        assertThat(states.state(actor, project)).isEqualTo(before);
        var create =
                new TaskRequests.Create(
                        "created", "body", TaskStatus.TODO, TaskType.TASK, 1, user, null);
        var first = tasks.create(actor, project, create, "realtime-key-" + project);
        var saved = states.state(actor, project);
        assertThat(tasks.create(actor, project, create, "realtime-key-" + project).id())
                .isEqualTo(first.id());
        assertThat(states.state(actor, project)).isEqualTo(saved);
        assertThat(maintenance.reconcile(project, false)).isTrue();
    }

    @Test
    void outsideUpstreamRecomputesVisibleDownstreamWithoutReturningOutsideNode() {
        UUID a = task("outside", "TODO"), b = task("visible", "TODO"), c = task("later", "TODO");
        edge(a, b);
        edge(b, c);
        var view =
                graph.view(actor, project, new GraphReadService.ViewRequest(List.of(b, c), null));
        assertThat(
                        view.nodes().stream()
                                .filter(n -> n.id().equals(b))
                                .findFirst()
                                .orElseThrow()
                                .isBlocked())
                .isTrue();
        status(a, "DONE");
        var d = delta(view.viewSeq(), b, c);
        assertThat(d.nodesUpsert()).extracting(GraphReadService.Node::id).containsExactly(b);
        assertThat(d.nodesUpsert().get(0).isBlocked()).isFalse();
        assertThat(d.nodesUpsert().get(0).visibleUpstreamCount()).isNull();
        assertThat(d.nodesUpsert().get(0).visibleDownstreamCount()).isNull();
        assertThat(d.edgesReplace()).isNull();
        assertThat(delta(view.viewSeq(), b, c)).isEqualTo(d);
    }

    @Test
    void initialFocusAndExpansionRespectBudgetsAndCompleteCrossEdges() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 75; i++) ids.add(task("node " + i, "TODO"));
        UUID root = ids.get(0);
        for (int i = 1; i < 75; i++) edge(root, ids.get(i));
        edge(ids.get(1), ids.get(2));
        var initial = graph.initial(actor, project, "overview", null, null, null);
        assertThat(initial.nodes()).hasSizeLessThanOrEqualTo(50);
        assertThat(initial.edges()).hasSizeLessThanOrEqualTo(150);
        assertThat(initial.scope().nodePartial()).isTrue();
        var focus = graph.initial(actor, project, "focus", root, 10, 20);
        assertThat(focus.nodes()).extracting(GraphReadService.Node::id).contains(root);
        var first =
                graph.view(
                        actor,
                        project,
                        new GraphReadService.ViewRequest(
                                List.of(root, ids.get(1)),
                                new GraphReadService.Expand(root, "DOWNSTREAM", null, 20)));
        assertThat(first.expansion().hasMore()).isTrue();
        assertThat(first.expansion().addedNodeIds()).hasSizeLessThanOrEqualTo(20);
        var nodeIds = first.nodes().stream().map(GraphReadService.Node::id).toList();
        var next =
                graph.view(
                        actor,
                        project,
                        new GraphReadService.ViewRequest(
                                nodeIds,
                                new GraphReadService.Expand(
                                        root, "DOWNSTREAM", first.expansion().nextCursor(), 20)));
        assertThat(next.nodes()).extracting(GraphReadService.Node::id).containsAll(nodeIds);
        if (next.nodes().stream().anyMatch(n -> n.id().equals(ids.get(2))))
            assertThat(next.edges()).contains(new Dependency(ids.get(1), ids.get(2)));
        task("topology change", "TODO");
        assertThatThrownBy(
                        () ->
                                graph.view(
                                        actor,
                                        project,
                                        new GraphReadService.ViewRequest(
                                                nodeIds,
                                                new GraphReadService.Expand(
                                                        root,
                                                        "DOWNSTREAM",
                                                        first.expansion().nextCursor(),
                                                        20))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code).isEqualTo("GRAPH_CURSOR_STALE"));
    }

    @Test
    void fullCanvasRetainsCursorAndInputValidationRejectsForeignScope() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 201; i++) ids.add(task("node " + i, "TODO"));
        for (int i = 1; i < 201; i++) edge(ids.get(0), ids.get(i));
        var full =
                graph.view(
                        actor,
                        project,
                        new GraphReadService.ViewRequest(
                                ids.subList(0, 200),
                                new GraphReadService.Expand(ids.get(0), "DOWNSTREAM", null, 20)));
        assertThat(full.expansion().addedNodeIds()).isEmpty();
        assertThat(full.expansion().nextCursor()).isNull();
        assertThat(full.expansion().hasMore()).isTrue();
        assertThatThrownBy(
                        () ->
                                graph.view(
                                        actor,
                                        project,
                                        new GraphReadService.ViewRequest(ids, null)))
                .isInstanceOf(ApiException.class);
        UUID other = UUID.randomUUID();
        db.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'other')",
                bytes(other),
                bytes(workspace));
        UUID foreign = UUID.randomUUID();
        db.update(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,'secret','','TODO','TASK','MANUAL',?)",
                bytes(foreign),
                bytes(other),
                bytes(user));
        assertThatThrownBy(
                        () ->
                                graph.view(
                                        actor,
                                        project,
                                        new GraphReadService.ViewRequest(List.of(foreign), null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void commentOnlySyncHasNoGraphEntitiesAndPreviewSlicesInSql() throws Exception {
        UUID a = task("完整标题🙂", "TODO");
        db.update(
                "UPDATE task SET description=CONCAT(REPEAT('🙂',999999),'尾') WHERE id=?", bytes(a));
        String after = states.state(actor, project).viewSeq();
        for (int i = 0; i < 52; i++)
            db.update(
                    "INSERT INTO comment(id,project_id,task_id,author_id,body,created_at) VALUES"
                        + " (?,?,?,?,REPEAT('评🙂',6000),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))",
                    bytes(UUID.randomUUID()),
                    bytes(project),
                    bytes(a),
                    bytes(user),
                    i);
        db.update("UPDATE task SET discussion_revision=52 WHERE id=?", bytes(a));
        var d = sync.sync(actor, project, new GraphSyncService.Request(after, List.of(a), a));
        assertThat(d.nodesUpsert()).isEmpty();
        assertThat(d.edgesReplace()).isNull();
        assertThat(d.previewDirtyTaskIds()).containsExactly(a);
        var v = previews.preview(actor, project, a, 5);
        assertThat(v.comments()).hasSize(5);
        assertThat(v.moreComments()).isTrue();
        assertThat(v.task().description().codePointCount(0, v.task().description().length()))
                .isEqualTo(2000);
        assertThat(v.task().descriptionTruncated()).isTrue();
        assertThat(
                        v.comments()
                                .get(0)
                                .body()
                                .codePointCount(0, v.comments().get(0).body().length()))
                .isEqualTo(1000);
        assertThat(json.writeValueAsBytes(v).length).isLessThan(131072);
    }

    @Test
    void expiredAndOversizedReplayRequireCurrentScopeRecovery() {
        UUID a = task("a", "TODO");
        String after = states.state(actor, project).viewSeq();
        tx.executeWithoutResult(
                t -> {
                    for (int i = 0; i < 1001; i++)
                        db.update("UPDATE task SET priority=? WHERE id=?", i % 2, bytes(a));
                });
        assertThatThrownBy(() -> delta(after, a))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code).isEqualTo("GRAPH_RESYNC_REQUIRED"));
        db.update(
                "UPDATE project_view_change SET created_at=UTC_TIMESTAMP()-INTERVAL 25 HOUR WHERE"
                        + " project_id=?",
                bytes(project));
        long cutoff = maintenance.prune(project);
        assertThat(cutoff).isGreaterThan(0);
        assertThatThrownBy(() -> delta(after, a)).isInstanceOf(ApiException.class);
        var restored =
                graph.view(actor, project, new GraphReadService.ViewRequest(List.of(a), null));
        assertThat(restored.nodes()).hasSize(1);
        assertThat(delta(restored.viewSeq(), a).nodesUpsert()).isEmpty();
    }

    @Test
    void maintenanceDetectsAndRepairsCounterDrift() {
        task("a", "TODO");
        db.update(
                "UPDATE project_overview_state SET todo_count=todo_count+10 WHERE project_id=?",
                bytes(project));
        assertThat(maintenance.reconcile(project, false)).isFalse();
        assertThat(maintenance.reconcile(project, true)).isFalse();
        assertThat(maintenance.reconcile(project, false)).isTrue();
        assertThat(states.state(actor, project).totalTasks()).isEqualTo(1);
    }

    @Test
    void metadataAndTitlesRemainBoundedForMaximumUnicodeInput() throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 200; i++) ids.add(task("🙂".repeat(500), "TODO"));
        var snapshot = graph.view(actor, project, new GraphReadService.ViewRequest(ids, null));
        assertThat(snapshot.nodes())
                .allSatisfy(
                        n -> {
                            assertThat(n.titleTruncated()).isTrue();
                            assertThat(n.title().codePointCount(0, n.title().length()))
                                    .isEqualTo(160);
                        });
        assertThat(json.writeValueAsBytes(snapshot).length).isLessThan(1048576);
        assertThat(
                        db.queryForObject(
                                "SELECT MAX(OCTET_LENGTH(changed_fields)) FROM project_view_change"
                                        + " WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isLessThan(4096);
    }

    @Test
    void highFanoutCompletionKeepsJournalBoundedAndUpdatesOnlyVisibleDownstream() {
        UUID root = task("upstream", "TODO");
        var ids = new ArrayList<UUID>();
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 10000; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new Object[] {bytes(id), bytes(project), bytes(user), bytes(user)});
        }
        tx.executeWithoutResult(
                v ->
                        db.batchUpdate(
                                "INSERT INTO"
                                    + " task(id,project_id,title,description,status,type,origin,assignee_id,created_by)"
                                    + " VALUES (?,?,'fanout','','TODO','TASK','MANUAL',?,?)",
                                rows));
        rows.clear();
        for (UUID id : ids)
            rows.add(new Object[] {bytes(project), bytes(root), bytes(id), bytes(user)});
        tx.executeWithoutResult(
                v ->
                        db.batchUpdate(
                                "INSERT INTO"
                                    + " task_dependency(project_id,blocker_id,blocked_id,created_by)"
                                    + " VALUES (?,?,?,?)",
                                rows));
        String before = states.state(actor, project).viewSeq();
        var patch = new TaskRequests.Patch();
        patch.setStatus(TaskStatus.DONE);
        long began = System.nanoTime();
        tasks.update(actor, project, root, 0, patch);
        System.out.println("OVERVIEW_FANOUT_WRITE_MS " + (System.nanoTime() - began) / 1_000_000.0);
        var visible = ids.subList(0, 20);
        var change = sync.sync(actor, project, new GraphSyncService.Request(before, visible, null));
        assertThat(change.nodesUpsert())
                .hasSize(20)
                .allSatisfy(n -> assertThat(n.isBlocked()).isFalse());
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM project_view_change WHERE project_id=? AND"
                                        + " seq>?",
                                Long.class,
                                bytes(project),
                                Long.parseLong(before)))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM activity WHERE project_id=? AND"
                                        + " kind='TASK_UNBLOCKED'",
                                Long.class,
                                bytes(project)))
                .isEqualTo(10000);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE project_id=?",
                                Long.class,
                                bytes(project)))
                .isEqualTo(10000);
        assertThat(maintenance.reconcile(project, false)).isTrue();
    }

    @Test
    void denseViewMakesEdgeOmissionExplicitAndDeletedNodesAreRemoved() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 50; i++) ids.add(task("dense " + i, "TODO"));
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 50; i++)
            for (int j = i + 1; j < 50; j++)
                rows.add(
                        new Object[] {
                            bytes(project), bytes(ids.get(i)), bytes(ids.get(j)), bytes(user)
                        });
        tx.executeWithoutResult(
                v ->
                        db.batchUpdate(
                                "INSERT INTO"
                                    + " task_dependency(project_id,blocker_id,blocked_id,created_by)"
                                    + " VALUES (?,?,?,?)",
                                rows));
        var view = graph.view(actor, project, new GraphReadService.ViewRequest(ids, null));
        assertThat(view.edges()).hasSize(1000);
        assertThat(view.scope().edgePartial()).isTrue();
        assertThat(view.nodes().stream().mapToInt(GraphReadService.Node::upstreamCount).sum())
                .isEqualTo(1225);
        UUID lone = task("removed", "TODO");
        String before = states.state(actor, project).viewSeq();
        db.update("DELETE FROM task WHERE id=?", bytes(lone));
        assertThat(delta(before, lone).nodesRemove()).containsExactly(lone);
    }

    @Test
    void githubObservationNoopAndCompletionKeepCanonicalCountersAndJournal() {
        Task imported =
                tx.execute(
                        t -> {
                            db.queryForList(
                                    "SELECT id FROM project WHERE id=? FOR UPDATE", bytes(project));
                            return tasks.applyGitHub(
                                    project,
                                    null,
                                    user,
                                    UUID.randomUUID(),
                                    "Imported issue",
                                    "upstream body",
                                    TaskStatus.TRIAGE);
                        });
        var state = states.state(actor, project);
        assertThat(state.counts().get("TRIAGE")).isEqualTo(1);
        assertThat(maintenance.reconcile(project, false)).isTrue();
        tx.executeWithoutResult(
                t -> {
                    db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", bytes(project));
                    tasks.applyGitHub(
                            project,
                            imported.id(),
                            user,
                            UUID.randomUUID(),
                            "Imported issue",
                            "upstream body",
                            TaskStatus.TRIAGE);
                });
        assertThat(states.state(actor, project)).isEqualTo(state);
        tx.executeWithoutResult(
                t -> {
                    db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", bytes(project));
                    tasks.applyGitHub(
                            project,
                            imported.id(),
                            user,
                            UUID.randomUUID(),
                            "Imported issue",
                            "confirmed body",
                            TaskStatus.DONE);
                });
        assertThat(states.state(actor, project).counts().get("DONE")).isEqualTo(1);
        assertThat(maintenance.reconcile(project, false)).isTrue();
        assertThat(delta(state.viewSeq(), imported.id()).nodesUpsert())
                .singleElement()
                .satisfies(n -> assertThat(n.status()).isEqualTo(TaskStatus.DONE));
    }

    record Login(HttpClient http, String csrfHeader, String csrfToken) {}

    Login login(UUID id) throws Exception {
        var client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        String base = "http://127.0.0.1:" + port;
        var token =
                json.readTree(
                        client.send(
                                        HttpRequest.newBuilder(URI.create(base + "/session/csrf"))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body());
        var response =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/session/login"))
                                .header(
                                        token.path("headerName").asText(),
                                        token.path("token").asText())
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "username=realtime-"
                                                        + id
                                                        + "&password=Realtime-fixture-2026%21"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
        token =
                json.readTree(
                        client.send(
                                        HttpRequest.newBuilder(URI.create(base + "/session/csrf"))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body());
        return new Login(client, token.path("headerName").asText(), token.path("token").asText());
    }

    @Test
    void liveSessionProjectTeamAndAccountRevocationCloseStreamsAndRejectConditionalReads()
            throws Exception {
        UUID task = task("visible", "TODO");
        for (String mode : List.of("PROJECT", "TEAM", "ACCOUNT", "SESSION")) {
            var login = login(member);
            String base = "http://127.0.0.1:" + port;
            var state =
                    login.http()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            base
                                                                    + "/projects/"
                                                                    + project
                                                                    + "/graph/state"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertThat(state.statusCode()).isEqualTo(200);
            String etag = state.headers().firstValue("ETag").orElseThrow();
            var stream =
                    login.http()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            base
                                                                    + "/projects/"
                                                                    + project
                                                                    + "/graph/events?after=0&connectionId="
                                                                    + UUID.randomUUID()))
                                            .header("Accept", "text/event-stream")
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(stream.statusCode()).isEqualTo(200);
            var ended =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return new String(
                                            stream.body().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8);
                                } catch (java.io.IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
            switch (mode) {
                case "PROJECT" ->
                        db.update(
                                "UPDATE project_member SET active=FALSE WHERE project_id=? AND"
                                        + " user_id=?",
                                bytes(project),
                                bytes(member));
                case "TEAM" ->
                        db.update(
                                "UPDATE workspace_member SET active=FALSE WHERE workspace_id=? AND"
                                        + " user_id=?",
                                bytes(workspace),
                                bytes(member));
                case "ACCOUNT" ->
                        db.update("UPDATE app_user SET active=FALSE WHERE id=?", bytes(member));
                case "SESSION" ->
                        db.update(
                                "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",
                                "realtime-" + member);
            }
            assertThat(ended.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .contains("access.revoked");
            var denied =
                    login.http()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            base
                                                                    + "/projects/"
                                                                    + project
                                                                    + "/graph/state"))
                                            .header("If-None-Match", etag)
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(
                            login.http()
                                    .send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    base
                                                                            + "/projects/"
                                                                            + project
                                                                            + "/tasks/"
                                                                            + task
                                                                            + "/preview"))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(403);
            db.update("UPDATE app_user SET active=TRUE WHERE id=?", bytes(member));
            db.update(
                    "UPDATE workspace_member SET active=TRUE WHERE workspace_id=? AND user_id=?",
                    bytes(workspace),
                    bytes(member));
            db.update(
                    "UPDATE project_member SET active=TRUE WHERE project_id=? AND user_id=?",
                    bytes(project),
                    bytes(member));
        }
    }

    @Test
    void readPostsKeepCsrfAndHardBodyBudgetAndOwnedConnectionClose() throws Exception {
        UUID id = task("a", "TODO");
        var login = login(user);
        String base = "http://127.0.0.1:" + port, path = base + "/projects/" + project;
        var rejected =
                login.http()
                        .send(
                                HttpRequest.newBuilder(URI.create(path + "/graph/view"))
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"nodeIds\":[]}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(403);
        var oversized =
                login.http()
                        .send(
                                HttpRequest.newBuilder(URI.create(path + "/graph/view"))
                                        .header("Content-Type", "application/json")
                                        .header(login.csrfHeader(), login.csrfToken())
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"nodeIds\":[]}" + " ".repeat(33000)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(oversized.statusCode()).isEqualTo(413);
        UUID connection = UUID.randomUUID();
        int before = streams.activeConnections();
        var stream =
                login.http()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        path
                                                                + "/graph/events?connectionId="
                                                                + connection))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(streams.activeConnections()).isEqualTo(before + 1);
        var closed =
                login.http()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        path
                                                                + "/graph/events?connectionId="
                                                                + connection))
                                        .header(login.csrfHeader(), login.csrfToken())
                                        .DELETE()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(closed.statusCode()).isEqualTo(204);
        assertThat(streams.activeConnections()).isEqualTo(before);
        stream.body().close();
    }

    @Test
    void permissionChangesAreRecheckedBeforeConditionalAndRangeReads() {
        UUID a = task("a", "TODO");
        var memberActor = new ActorContext(member);
        states.state(memberActor, project);
        db.update(
                "UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",
                bytes(project),
                bytes(member));
        assertThatThrownBy(() -> states.state(memberActor, project))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(
                        () ->
                                graph.view(
                                        memberActor,
                                        project,
                                        new GraphReadService.ViewRequest(List.of(a), null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> previews.preview(memberActor, project, a, 5))
                .isInstanceOf(ApiException.class);
    }
}
