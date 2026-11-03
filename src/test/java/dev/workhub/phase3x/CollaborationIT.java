package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.importing.*;
import dev.workhub.messaging.*;
import dev.workhub.onboarding.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.util.*;

@SpringBootTest(
        properties = {
            "debug=false",
            "workhub.bootstrap.login=",
            "workhub.messaging.enabled=false",
            "workhub.github.enabled=false"
        })
class CollaborationIT {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry p) {
        p.add(
                "spring.datasource.url",
                () ->
                        "jdbc:mysql://127.0.0.1:"
                                + System.getenv().getOrDefault("MYSQL_PORT", "13306")
                                + "/workhub_phase3x_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC");
    }

    @Autowired JdbcTemplate db;
    @Autowired Accounts accounts;
    @Autowired MailService mail;
    @Autowired Workspaces spaces;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired CsvImports csv;
    @Autowired WorkJobExecutor executor;
    @Autowired CollaborationService collaboration;
    ActorContext alice, bob;
    UUID w, p;

    @BeforeEach
    void fixture() {
        alice = user("alice");
        bob = user("bob");
        w = spaces.create(alice, "Workspace");
        invite(bob, w, alice);
        p = projects.create(alice, w, "Product", UUID.randomUUID().toString()).id();
        projects.member(
                alice,
                p,
                bob.userId(),
                ProjectRole.MEMBER,
                true,
                projects.get(alice, p).businessRevision());
    }

    ActorContext user(String prefix) {
        String login = prefix + UUID.randomUUID().toString().replace("-", "");
        accounts.register(login, login + "@example.test", prefix, "test-password-12345");
        String token = token(login + "@example.test");
        accounts.consume(token, "verify", null);
        return new ActorContext(
                db.queryForObject(
                        "SELECT id FROM app_user WHERE login=?", (r, n) -> read(r, "id"), login));
    }

    String email(ActorContext a) {
        return db.queryForObject(
                "SELECT email FROM app_user WHERE id=?", String.class, bytes(a.userId()));
    }

    String token(String email) {
        String encrypted =
                db.queryForObject(
                        "SELECT encrypted_body FROM mail_delivery WHERE recipient=? ORDER BY"
                                + " created_at DESC LIMIT 1",
                        String.class,
                        email);
        String text = mail.decrypt(encrypted);
        return text.split("/#(?:verify|invite|reset)/")[1].split("\\s")[0];
    }

    void invite(ActorContext target, UUID workspace, ActorContext admin) {
        spaces.invite(admin, workspace, email(target));
        spaces.accept(target, token(email(target)));
    }

    @Test
    void projectSummaryCountsOnlyAuthorizedProjectIncludingEmptyStates() {
        assertThat(collaboration.summary(alice, p).values()).allMatch(n -> n == 0L);
        tasks.create(bob, p, new TaskRequests.Create("Needs review", "", TaskStatus.TRIAGE,
                null, null, null, null), UUID.randomUUID().toString());
        UUID other = projects.create(alice, w, "Other", UUID.randomUUID().toString()).id();
        tasks.create(alice, other, new TaskRequests.Create("Unrelated", "", TaskStatus.TODO,
                null, null, null, null), UUID.randomUUID().toString());
        assertThat(collaboration.summary(bob, p)).containsEntry("TRIAGE", 1L).containsEntry("TODO", 0L);
        assertThatThrownBy(() -> collaboration.summary(bob, other)).isInstanceOf(ApiException.class);
    }

    @Test
    void invitationsAndWorkspaceIsolation() {
        UUID other = spaces.create(bob, "Other");
        UUID privateProject =
                projects.create(bob, other, "Secret", UUID.randomUUID().toString()).id();
        assertThatThrownBy(() -> projects.get(alice, privateProject))
                .isInstanceOf(ApiException.class);
        spaces.membership(alice, w, bob.userId(), "MEMBER", false);
        assertThatThrownBy(() -> projects.get(bob, p)).isInstanceOf(ApiException.class);
        assertThat(projects.get(bob, privateProject)).isNotNull();
        invite(bob, w, alice);
        assertThatThrownBy(() -> projects.get(bob, p)).isInstanceOf(ApiException.class);
        String accepted = token(email(bob));
        assertThat(spaces.accept(bob, accepted)).isEqualTo(w);
    }

    @Test
    void revokedExpiredAndWrongRecipientInvitations() {
        ActorContext third = user("third");
        UUID id = spaces.invite(alice, w, email(third));
        String token = token(email(third));
        assertThat(spaces.invite(alice, w, email(third))).isEqualTo(id);
        assertThatThrownBy(() -> spaces.accept(bob, token)).isInstanceOf(ApiException.class);
        spaces.revoke(alice, w, id);
        assertThatThrownBy(() -> spaces.accept(third, token)).isInstanceOf(ApiException.class);
        UUID next = spaces.invite(alice, w, email(third));
        db.update(
                "UPDATE workspace_invitation SET expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(next));
        assertThatThrownBy(() -> spaces.accept(third, token(email(third))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void lastRolesAndControlledHandover() {
        assertThatThrownBy(() -> spaces.membership(alice, w, alice.userId(), "MEMBER", false))
                .hasMessageContaining("管理员");
        assertThatThrownBy(
                        () ->
                                projects.member(
                                        alice,
                                        p,
                                        alice.userId(),
                                        ProjectRole.MEMBER,
                                        true,
                                        projects.get(alice, p).businessRevision()))
                .hasMessageContaining("交接");
        spaces.membership(alice, w, bob.userId(), "ADMIN", true);
        spaces.handover(bob, w, p, bob.userId());
        spaces.membership(bob, w, alice.userId(), "MEMBER", false);
        assertThat(projects.get(bob, p)).isNotNull();
        assertThatThrownBy(() -> projects.get(alice, p)).isInstanceOf(ApiException.class);
    }

    @Test
    void csvPartialIdempotencyArchiveAndResume() {
        String source =
                "title,description,type,priority,due_date,assignee_login\n"
                        + "First,hello,TASK,2,,\n"
                        + "Bad,,TASK,8,,\n"
                        + "Second,,BUG,1,,\n";
        String key = UUID.randomUUID().toString();
        UUID id = csv.upload(alice, p, "tasks.csv", source.getBytes(), key);
        assertThat(csv.upload(alice, p, "tasks.csv", source.getBytes(), key)).isEqualTo(id);
        csv.confirm(alice, p, id, false);
        var jobs = csvJobs(id);
        deliver(jobs.get(0));
        deliver(jobs.get(0));
        var pr = projects.get(alice, p);
        collaboration.project(alice, p, pr.name(), "", true, pr.businessRevision());
        deliver(jobs.get(1));
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("STOPPED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(1);
        pr = projects.get(alice, p);
        collaboration.project(alice, p, pr.name(), "", false, pr.businessRevision());
        csv.confirm(alice, p, id, true);
        for (var job : csvJobs(id)) deliver(job);
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("PARTIAL_FAILED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(2);
        assertThat(csv.view(alice, p, id).get("counts"))
                .isEqualTo(Map.of("SUCCEEDED", 2L, "FAILED", 1L, "PENDING", 0L));
    }

    @Test
    void revokeStopsPendingImportAndReportAccess() {
        projects.member(
                alice,
                p,
                bob.userId(),
                ProjectRole.MAINTAINER,
                true,
                projects.get(alice, p).businessRevision());
        String source =
                "title,description,type,priority,due_date,assignee_login\n"
                        + "First,,TASK,2,,\n"
                        + "Second,,TASK,1,,\n";
        UUID id = csv.upload(bob, p, "tasks.csv", source.getBytes(), UUID.randomUUID().toString());
        csv.confirm(bob, p, id, false);
        var jobs = csvJobs(id);
        deliver(jobs.get(0));
        spaces.membership(alice, w, bob.userId(), "MEMBER", false);
        deliver(jobs.get(1));
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("STOPPED");
        assertThatThrownBy(() -> csv.report(bob, p, id)).isInstanceOf(ApiException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(1);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @Test
    void concurrentAdminAndMaintainerDowngradesKeepOne() throws Exception {
        spaces.membership(alice, w, bob.userId(), "ADMIN", true);
        spaces.handover(alice, w, p, bob.userId());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                try {
                                    spaces.membership(alice, w, alice.userId(), "MEMBER", true);
                                    return true;
                                } catch (ApiException e) {
                                    return false;
                                }
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                try {
                                    spaces.membership(bob, w, bob.userId(), "MEMBER", true);
                                    return true;
                                } catch (ApiException e) {
                                    return false;
                                }
                            });
            gate.countDown();
            assertThat(
                            a.get(10, java.util.concurrent.TimeUnit.SECONDS)
                                    ^ b.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            long revision = projects.get(alice, p).businessRevision();
            var gate2 = new java.util.concurrent.CountDownLatch(1);
            var x =
                    pool.submit(
                            () -> {
                                gate2.await();
                                try {
                                    projects.member(
                                            alice,
                                            p,
                                            alice.userId(),
                                            ProjectRole.MEMBER,
                                            true,
                                            revision);
                                    return true;
                                } catch (ApiException e) {
                                    return false;
                                }
                            });
            var y =
                    pool.submit(
                            () -> {
                                gate2.await();
                                try {
                                    projects.member(
                                            bob,
                                            p,
                                            bob.userId(),
                                            ProjectRole.MEMBER,
                                            true,
                                            revision);
                                    return true;
                                } catch (ApiException e) {
                                    return false;
                                }
                            });
            gate2.countDown();
            assertThat(
                            x.get(10, java.util.concurrent.TimeUnit.SECONDS)
                                    ^ y.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM project_member WHERE project_id=? AND"
                                            + " active AND role='MAINTAINER'",
                                    Integer.class,
                                    bytes(p)))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void triageCancellationCannotBypassAcceptanceAndVersionsConflict() {
        Task t =
                tasks.create(
                        bob,
                        p,
                        new TaskRequests.Create(
                                "Triage", "", TaskStatus.TRIAGE, null, null, null, null),
                        UUID.randomUUID().toString());
        var patch = new TaskRequests.Patch();
        patch.setStatus(TaskStatus.CANCELED);
        t = tasks.update(bob, p, t.id(), 0, patch);
        var next = new TaskRequests.Patch();
        next.setStatus(TaskStatus.TODO);
        UUID id = t.id();
        long rev = t.revision();
        assertThatThrownBy(() -> tasks.update(bob, p, id, rev, next))
                .satisfies(
                        e -> assertThat(((ApiException) e).code).isEqualTo("TASK_EDIT_FORBIDDEN"));
        next.setStatus(TaskStatus.TRIAGE);
        tasks.update(alice, p, id, rev, next);
        assertThatThrownBy(() -> tasks.update(bob, p, id, rev, next))
                .isInstanceOf(ApiException.class);
        var accept = new TaskRequests.Patch();
        accept.setAssigneeId(bob.userId());
        tasks.acceptTriage(alice, p, id, rev + 1, accept);
        assertThat(tasks.get(bob, p, id).status()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void realProcessExitAfterPartialCommitResumesWithoutDuplicates() throws Exception {
        String source =
                "title,description,type,priority,due_date,assignee_login\n"
                        + "First,,TASK,2,,\n"
                        + "Second,,TASK,1,,\n";
        UUID id =
                csv.upload(alice, p, "crash.csv", source.getBytes(), UUID.randomUUID().toString());
        csv.confirm(alice, p, id, false);
        var jobs = csvJobs(id);
        java.nio.file.Path log = java.nio.file.Path.of("target/csv-crash-" + id + ".log");
        var child =
                new ProcessBuilder(
                                java.nio.file.Path.of(System.getProperty("java.home"), "bin/java")
                                        .toString(),
                                "-Xmx192m",
                                "-XX:ActiveProcessorCount=2",
                                "-cp",
                                System.getProperty("java.class.path"),
                                CsvCrashWorker.class.getName(),
                                jobs.get(0).toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        assertThat(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isEqualTo(23);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(1);
        for (UUID job : jobs) {
            deliver(job);
            deliver(job);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(2);
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("SUCCEEDED");
    }

    @Test
    void scopedAdminCannotReadProjectUntilExplicitHandover() {
        UUID secret = projects.create(bob, w, "Bob private", UUID.randomUUID().toString()).id();
        assertThat(spaces.projects(alice, w)).hasSize(2);
        assertThatThrownBy(() -> projects.get(alice, secret)).isInstanceOf(ApiException.class);
        spaces.handover(alice, w, secret, alice.userId());
        assertThat(projects.get(alice, secret)).isNotNull();
    }

    @Test
    void passwordResetSingleUseAndOldSessionsAreDeleted() {
        accounts.request(email(alice), "reset");
        // Delivery timestamps can move backwards when the local Docker clock is adjusted.
        // Select the reset message by purpose instead of mistaking a newer-dated verify mail for
        // it.
        String resetMail =
                db
                        .queryForList(
                                "SELECT encrypted_body FROM mail_delivery WHERE recipient=? AND"
                                        + " encrypted_body IS NOT NULL",
                                String.class,
                                email(alice))
                        .stream()
                        .map(mail::decrypt)
                        .filter(text -> text.contains("/#reset/"))
                        .findFirst()
                        .orElseThrow();
        String token = resetMail.split("/#reset/")[1].split("\\s")[0];
        String login = (String) accounts.profile(alice).get("login");
        db.update(
                "INSERT INTO"
                    + " SPRING_SESSION(PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,PRINCIPAL_NAME)"
                    + " VALUES (?,?,0,0,1800,9999999999999,?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                login);
        accounts.consume(token, "reset", "new-password-12345");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",
                                Integer.class,
                                login))
                .isZero();
        assertThatThrownBy(() -> accounts.consume(token, "reset", "other-password-12345"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void representativePaginationAndIndexEvidence() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < 3000; n++)
            rows.add(
                    new Object[] {
                        bytes(UUID.randomUUID()), bytes(p), "Scale task " + n, bytes(alice.userId())
                    });
        db.batchUpdate(
                "INSERT INTO task(id,project_id,title,description,status,type,origin,created_by)"
                        + " VALUES (?,?,?,'','TODO','TASK','MANUAL',?)",
                rows);
        long start = System.nanoTime();
        var first = collaboration.query(alice, p, "Scale", "TODO", null, null, null, 0, 50);
        var second = collaboration.query(alice, p, "Scale", "TODO", null, null, null, 50, 50);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(first.items()).hasSize(50);
        assertThat(second.items()).hasSize(50);
        assertThat(first.items().stream().map(TaskResponse::id).toList())
                .doesNotContainAnyElementsOf(
                        second.items().stream().map(TaskResponse::id).toList());
        assertThat(first.hasMore()).isTrue();
        assertThat(ms).isLessThan(5000);
        var explain =
                db.queryForList(
                        "EXPLAIN SELECT id FROM task WHERE project_id=? AND status='TODO' ORDER BY"
                                + " updated_at DESC,id DESC LIMIT 50",
                        bytes(p));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/evidence/phase3x"));
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("rows", 3000);
        evidence.put("pages", 2);
        evidence.put("pageSize", 50);
        evidence.put("elapsedMillis", ms);
        evidence.put("explain", explain);
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/evidence/phase3x/pagination.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(evidence));
    }

    @Test
    void failedRowTransactionHasNoPartialTaskAndCanSafelyRetry() {
        UUID id =
                csv.upload(
                        alice,
                        p,
                        "rollback.csv",
                        ("title,description,type,priority,due_date,assignee_login\n"
                                        + "Injected failure "
                                        + p
                                        + ",,TASK,2,,\n")
                                .getBytes(),
                        UUID.randomUUID().toString());
        csv.confirm(alice, p, id, false);
        db.execute(
                "ALTER TABLE task ADD CONSTRAINT phase3x_failure CHECK(title<>'Injected failure "
                        + p
                        + "')");
        try {
            deliver(csvJobs(id).get(0));
        } finally {
            db.execute("ALTER TABLE task DROP CHECK phase3x_failure");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isZero();
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("FAILED");
        csv.confirm(alice, p, id, true);
        for (UUID job : csvJobs(id)) deliver(job);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(1);
        assertThat(csv.view(alice, p, id).get("state")).isEqualTo("SUCCEEDED");
    }

    @Test
    void thousandRowImportIsBoundedAndCountsMatchCommittedTasks() throws Exception {
        StringBuilder file =
                new StringBuilder("title,description,type,priority,due_date,assignee_login\n");
        for (int n = 0; n < 1000; n++)
            file.append("Bulk ").append(n).append(",fixed snapshot,TASK,2,,\n");
        byte[] input = file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long start = System.nanoTime();
        UUID id = csv.upload(alice, p, "1000.csv", input, UUID.randomUUID().toString());
        csv.confirm(alice, p, id, false);
        for (UUID job : csvJobs(id)) deliver(job);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(csv.view(alice, p, id).get("counts"))
                .isEqualTo(Map.of("SUCCEEDED", 1000L, "FAILED", 0L, "PENDING", 0L));
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM task WHERE project_id=?",
                                Integer.class,
                                bytes(p)))
                .isEqualTo(1000);
        var result =
                Map.of(
                        "rows",
                        1000,
                        "inputBytes",
                        input.length,
                        "elapsedMillis",
                        ms,
                        "maxHeapBytes",
                        Runtime.getRuntime().maxMemory(),
                        "transactionRows",
                        1);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/evidence/phase3x"));
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/evidence/phase3x/import-scale.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result));
    }

    List<UUID> csvJobs(UUID id) {
        return db.query(
                "SELECT j.id FROM work_job j WHERE j.owner_ref=? AND j.handler='CSV_ROW' ORDER BY"
                    + " CAST(JSON_UNQUOTE(JSON_EXTRACT(j.payload,'$.generation')) AS"
                    + " UNSIGNED),CAST(JSON_UNQUOTE(JSON_EXTRACT(j.payload,'$.row')) AS UNSIGNED)",
                (r, n) -> read(r, "id"),
                bytes(id));
    }

    void deliver(UUID job) {
        var row =
                db.queryForMap(
                        "SELECT BIN_TO_UUID(id) id,wire_payload FROM outbox_message WHERE job_id=?"
                                + " ORDER BY dispatch_seq DESC LIMIT 1",
                        bytes(job));
        executor.handle(
                (String) row.get("id"),
                "business.work",
                ((String) row.get("wire_payload"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
