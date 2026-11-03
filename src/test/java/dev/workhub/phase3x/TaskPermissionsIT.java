package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "debug=false",
            "workhub.bootstrap.login=",
            "workhub.messaging.enabled=false",
            "workhub.github.enabled=false"
        })
@AutoConfigureMockMvc
class TaskPermissionsIT {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry p) {
        CollaborationIT.db(p);
    }

    @Autowired JdbcTemplate db;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired ContentService content;
    @Autowired DependencyService dependencies;
    @Autowired CollaborationService collaboration;
    @Autowired TaskReassignments reassignments;
    @Autowired TaskReassignmentRepository requests;
    @Autowired MockMvc http;
    @Autowired Json json;
    @Autowired dev.workhub.importing.CsvImports csv;
    ActorContext maintainer, creator, assignee, observer;
    UUID workspace, project;
    Task task;

    @BeforeEach
    void fixture() {
        workspace = UUID.randomUUID();
        db.update("INSERT INTO workspace(id,name) VALUES (?,'task permissions')", bytes(workspace));
        maintainer = newActor();
        creator = newActor();
        assignee = newActor();
        observer = newActor();
        project =
                projects.create(
                                maintainer,
                                workspace,
                                "Task approvals",
                                UUID.randomUUID().toString())
                        .id();
        for (var actor : List.of(creator, assignee, observer))
            projects.member(
                    maintainer,
                    project,
                    actor.userId(),
                    ProjectRole.MEMBER,
                    true,
                    projects.get(maintainer, project).businessRevision());
        task =
                tasks.create(
                        creator,
                        project,
                        new TaskRequests.Create(
                                "A proposed task",
                                "Original content",
                                null,
                                null,
                                null,
                                null,
                                null),
                        UUID.randomUUID().toString());
    }

    ActorContext newActor() {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",
                bytes(id),
                login(id),
                "Task member");
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",
                bytes(workspace),
                bytes(id));
        return new ActorContext(id);
    }

    String login(UUID id) {
        return "task" + id;
    }

    String base() {
        return "/projects/" + project + "/tasks/" + task.id();
    }

    long revision() {
        return tasks.get(maintainer, project, task.id()).revision();
    }

    void patchTask(ActorContext actor, String body, long revision, int expected) throws Exception {
        http.perform(
                        patch(base())
                                .with(user(login(actor.userId())))
                                .with(csrf())
                                .contentType("application/json")
                                .header("If-Match", "\"" + revision + "\"")
                                .content(body))
                .andExpect(status().is(expected));
    }

    Task accept() {
        var patch = new TaskRequests.Patch();
        patch.setAssigneeId(assignee.userId());
        patch.setPriority(1);
        patch.setDueDate(java.time.LocalDate.of(2026, 10, 10));
        return tasks.acceptTriage(maintainer, project, task.id(), revision(), patch);
    }

    Task upstream() {
        return tasks.create(
                maintainer,
                project,
                new TaskRequests.Create("Upstream", "", null, null, null, null, null),
                UUID.randomUUID().toString());
    }

    TaskReassignmentRepository.Request request() {
        return reassignments.create(
                assignee,
                project,
                task.id(),
                new TaskReassignments.Create("Need a handover", revision()),
                UUID.randomUUID().toString());
    }

    void code(Throwable e, String code) {
        assertThat(e).isInstanceOf(ApiException.class);
        assertThat(((ApiException) e).code).isEqualTo(code);
    }

    @Test
    void membersSubmitUnassignedTriageAndCannotChooseFormalArrangement() throws Exception {
        assertThat(task.status()).isEqualTo(TaskStatus.TRIAGE);
        assertThat(task.triageRequired()).isTrue();
        assertThat(task.assigneeId()).isNull();
        http.perform(get(base() + "/detail").with(user(login(creator.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canPrepareTask").value(true))
                .andExpect(jsonPath("$.canManageDependencies").value(true));
        for (String body :
                List.of(
                        "{\"title\":\"Bypass\",\"status\":\"TODO\"}",
                        "{\"title\":\"Bypass\",\"priority\":0}",
                        "{\"title\":\"Bypass\",\"assigneeId\":\"" + assignee.userId() + "\"}"))
            http.perform(
                            post("/projects/" + project + "/tasks")
                                    .with(user(login(creator.userId())))
                                    .with(csrf())
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isForbidden());
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification n JOIN activity a ON"
                                    + " a.id=n.activity_id WHERE a.task_id=? AND n.recipient_id=?"
                                    + " AND a.kind='TASK_SUBMITTED'",
                                Integer.class,
                                bytes(task.id()),
                                bytes(maintainer.userId())))
                .isEqualTo(1);
    }

    @Test
    void creatorPreparesProposalAndDependenciesButCannotAdvanceOrAssign() throws Exception {
        http.perform(
                        post(base() + "/github/intents")
                                .with(user(login(creator.userId())))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(
                                        "{\"expectedTaskRevision\":0,\"expectedSyncRevision\":0,\"desired\":{\"state\":\"closed\",\"state_reason\":\"completed\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TASK_APPROVAL_REQUIRED"));
        patchTask(
                creator,
                "{\"title\":\"Refined request\",\"description\":\"More"
                        + " details\",\"dueDate\":\"2026-10-09\"}",
                0,
                200);
        for (String body :
                List.of(
                        "{\"status\":\"IN_PROGRESS\"}",
                        "{\"priority\":0}",
                        "{\"type\":\"BUG\"}",
                        "{\"assigneeId\":\"" + assignee.userId() + "\"}"))
            patchTask(creator, body, 1, 403);
        patchTask(assignee, "{\"description\":\"Not assigned yet\"}", 1, 403);
        Task up = upstream();
        var edge = new Dependency(up.id(), task.id());
        dependencies.add(
                creator,
                project,
                projects.get(creator, project).businessRevision(),
                edge,
                UUID.randomUUID().toString());
        assertThat(tasks.get(creator, project, task.id()).isBlocked()).isTrue();
        assertThatThrownBy(
                        () ->
                                dependencies.add(
                                        creator,
                                        project,
                                        projects.get(creator, project).businessRevision(),
                                        new Dependency(task.id(), up.id()),
                                        UUID.randomUUID().toString()))
                .satisfies(e -> code(e, "TASK_EDIT_FORBIDDEN"));
        dependencies.remove(
                creator, project, projects.get(creator, project).businessRevision(), edge);
        assertThat(tasks.get(creator, project, task.id()).isBlocked()).isFalse();
    }

    @Test
    void acceptanceAssignsAndRevokesCreatorInOneVersionedOperation() throws Exception {
        http.perform(
                        post(base() + "/triage-accept")
                                .with(user(login(maintainer.userId())))
                                .with(csrf())
                                .header("If-Match", "\"0\"")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnprocessableEntity());
        http.perform(
                        post(base() + "/triage-accept")
                                .with(user(login(creator.userId())))
                                .with(csrf())
                                .header("If-Match", "\"0\"")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        Task accepted = accept();
        assertThat(accepted.status()).isEqualTo(TaskStatus.TODO);
        assertThat(accepted.triageRequired()).isFalse();
        assertThat(accepted.assigneeId()).isEqualTo(assignee.userId());
        assertThat(accepted.priority()).isEqualTo(1);
        assertThat(accepted.revision()).isEqualTo(1);
        assertThat(collaboration.detail(creator, project, task.id()))
                .containsEntry("canEditTask", false)
                .containsEntry("canManageDependencies", false);
        patchTask(creator, "{\"description\":\"Old creator draft\"}", 0, 403);
        patchTask(assignee, "{\"description\":\"Old version\"}", 0, 409);
        patchTask(
                assignee,
                "{\"description\":\"Execution update\",\"status\":\"IN_PROGRESS\"}",
                1,
                200);
        content.createComment(
                creator, project, task.id(), "Still able to discuss", UUID.randomUUID().toString());
    }

    @Test
    void assigneeCanExecuteButCannotChangeApprovedScopeOrScheduling() throws Exception {
        accept();
        for (String body :
                List.of(
                        "{\"title\":\"Scope change\"}",
                        "{\"dueDate\":null}",
                        "{\"type\":\"BUG\"}",
                        "{\"priority\":0}",
                        "{\"status\":\"TRIAGE\"}",
                        "{\"status\":\"CANCELED\"}",
                        "{\"assigneeId\":\"" + observer.userId() + "\"}"))
            patchTask(assignee, body, 1, 403);
        Task up = upstream();
        var edge = new Dependency(up.id(), task.id());
        dependencies.add(
                assignee,
                project,
                projects.get(assignee, project).businessRevision(),
                edge,
                UUID.randomUUID().toString());
        patchTask(assignee, "{\"status\":\"DONE\"}", 1, 200);
        assertThat(tasks.get(assignee, project, task.id()).isBlocked()).isTrue();
        patchTask(assignee, "{\"status\":\"TODO\"}", 2, 200);
        http.perform(
                        post(base() + "/github/intents")
                                .with(user(login(assignee.userId())))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(
                                        "{\"expectedTaskRevision\":3,\"expectedSyncRevision\":0,\"desired\":{\"title\":\"Bypass\"}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withdrawnOrRejectedProposalCannotBypassAnotherAcceptance() throws Exception {
        patchTask(creator, "{\"status\":\"CANCELED\"}", 0, 200);
        patchTask(creator, "{\"status\":\"TODO\"}", 1, 403);
        patchTask(creator, "{\"status\":\"TRIAGE\"}", 1, 403);
        patchTask(maintainer, "{\"status\":\"TODO\"}", 1, 422);
        patchTask(maintainer, "{\"status\":\"TRIAGE\"}", 1, 200);
        assertThat(collaboration.detail(creator, project, task.id()))
                .containsEntry("canPrepareTask", true);
        patchTask(maintainer, "{\"status\":\"CANCELED\"}", 2, 200);
        patchTask(creator, "{\"description\":\"Rejected draft\"}", 3, 403);
    }

    @Test
    void reassignmentRequestNotifiesMaintainersAndApprovalChangesAuthorityAtomically()
            throws Exception {
        accept();
        String key = UUID.randomUUID().toString();
        var input = new TaskReassignments.Create("Schedule conflict", revision());
        var request = reassignments.create(assignee, project, task.id(), input, key);
        assertThat(reassignments.create(assignee, project, task.id(), input, key).id())
                .isEqualTo(request.id());
        assertThatThrownBy(this::request).satisfies(e -> code(e, "REASSIGNMENT_PENDING"));
        assertThatThrownBy(
                        () ->
                                reassignments.create(
                                        creator,
                                        project,
                                        task.id(),
                                        input,
                                        UUID.randomUUID().toString()))
                .satisfies(e -> code(e, "REASSIGNMENT_FORBIDDEN"));
        assertThat(tasks.get(assignee, project, task.id()).assigneeId())
                .isEqualTo(assignee.userId());
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification n JOIN activity a ON"
                                    + " a.id=n.activity_id WHERE a.task_id=? AND n.recipient_id=?"
                                    + " AND a.kind='REASSIGNMENT_REQUESTED'",
                                Integer.class,
                                bytes(task.id()),
                                bytes(maintainer.userId())))
                .isEqualTo(1);
        patchTask(assignee, "{\"description\":\"Still executing\"}", 1, 200);
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        maintainer,
                                        project,
                                        task.id(),
                                        request.id(),
                                        0,
                                        new TaskReassignments.Resolve(
                                                "APPROVE", observer.userId(), "", 1L)))
                .satisfies(e -> code(e, "TASK_REVISION_CONFLICT"));
        assertThat(requests.find(project, task.id(), request.id()).orElseThrow().status())
                .isEqualTo("PENDING");
        var resolved =
                reassignments.resolve(
                        maintainer,
                        project,
                        task.id(),
                        request.id(),
                        0,
                        new TaskReassignments.Resolve(
                                "APPROVE", observer.userId(), "Please take over", revision()));
        assertThat(resolved.status()).isEqualTo("APPROVED");
        assertThat(tasks.get(observer, project, task.id()).assigneeId())
                .isEqualTo(observer.userId());
        patchTask(assignee, "{\"description\":\"Old assignee\"}", revision(), 403);
        patchTask(observer, "{\"description\":\"New assignee\"}", revision(), 200);
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        maintainer,
                                        project,
                                        task.id(),
                                        request.id(),
                                        1,
                                        new TaskReassignments.Resolve(
                                                "REJECT", null, "", revision())))
                .satisfies(e -> code(e, "REQUEST_RESOLVED"));
    }

    @Test
    void rejectionRetainsAssigneeAndDirectReassignmentSupersedesOpenRequest() throws Exception {
        accept();
        var first = request();
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        creator,
                                        project,
                                        task.id(),
                                        first.id(),
                                        0,
                                        new TaskReassignments.Resolve(
                                                "REJECT", null, "", revision())))
                .satisfies(e -> code(e, "FORBIDDEN"));
        reassignments.resolve(
                maintainer,
                project,
                task.id(),
                first.id(),
                0,
                new TaskReassignments.Resolve("REJECT", null, "Continue for now", revision()));
        assertThat(tasks.get(assignee, project, task.id()).assigneeId())
                .isEqualTo(assignee.userId());
        var second = request();
        patchTask(maintainer, "{\"assigneeId\":\"" + observer.userId() + "\"}", revision(), 200);
        assertThat(requests.find(project, task.id(), second.id()).orElseThrow().status())
                .isEqualTo("SUPERSEDED");
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        maintainer,
                                        project,
                                        task.id(),
                                        second.id(),
                                        1,
                                        new TaskReassignments.Resolve(
                                                "APPROVE", creator.userId(), "", revision())))
                .satisfies(e -> code(e, "REQUEST_RESOLVED"));
    }

    @Test
    void invalidReplacementRollsBackDecisionAndAssignment() {
        accept();
        var request = request();
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        maintainer,
                                        project,
                                        task.id(),
                                        request.id(),
                                        0,
                                        new TaskReassignments.Resolve(
                                                "APPROVE", UUID.randomUUID(), "", revision())))
                .satisfies(e -> code(e, "VALIDATION_ERROR"));
        assertThat(requests.find(project, task.id(), request.id()).orElseThrow().status())
                .isEqualTo("PENDING");
        assertThat(tasks.get(assignee, project, task.id()).assigneeId())
                .isEqualTo(assignee.userId());
    }

    @Test
    void concurrentRequestsAndReviewsCannotCreateTwoPendingOrAssignTwice() throws Exception {
        accept();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> create =
                    () -> {
                        gate.await();
                        try {
                            request();
                            return true;
                        } catch (ApiException e) {
                            assertThat(e.code).isEqualTo("REASSIGNMENT_PENDING");
                            return false;
                        }
                    };
            var a = pool.submit(create);
            var b = pool.submit(create);
            gate.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) ^ b.get(10, TimeUnit.SECONDS)).isTrue();
            var request = requests.list(project, task.id()).get(0);
            long before = revision();
            var reviews = new CountDownLatch(1);
            Callable<Boolean> review =
                    () -> {
                        reviews.await();
                        try {
                            reassignments.resolve(
                                    maintainer,
                                    project,
                                    task.id(),
                                    request.id(),
                                    0,
                                    new TaskReassignments.Resolve(
                                            "APPROVE", observer.userId(), "", before));
                            return true;
                        } catch (ApiException e) {
                            assertThat(e.code).isEqualTo("REQUEST_REVISION_CONFLICT");
                            return false;
                        }
                    };
            var x = pool.submit(review);
            var y = pool.submit(review);
            reviews.countDown();
            assertThat(x.get(10, TimeUnit.SECONDS) ^ y.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(revision()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void creatorAssignedToOwnAcceptedTaskGetsOneNotificationWithBothReasons() {
        var patch = new TaskRequests.Patch();
        patch.setAssigneeId(creator.userId());
        tasks.acceptTriage(maintainer, project, task.id(), 0, patch);
        assertThat(collaboration.detail(creator, project, task.id()))
                .containsEntry("canEditTask", true);
        var reasons =
                db.queryForList(
                        "SELECT n.reason_set FROM notification n JOIN activity a ON"
                            + " a.id=n.activity_id WHERE a.task_id=? AND a.kind='TASK_ACCEPTED' AND"
                            + " n.recipient_id=?",
                        String.class,
                        bytes(task.id()),
                        bytes(creator.userId()));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("ASSIGNED", "TRIAGE_DECISION");
    }

    @Test
    void csvFormalArrangementRequiresMaintainerEvenForOriginalUploader() {
        byte[] source =
                "title,description,type,priority,due_date,assignee_login\nBulk task,,TASK,2,,\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                csv.upload(
                                        creator,
                                        project,
                                        "tasks.csv",
                                        source,
                                        UUID.randomUUID().toString()))
                .satisfies(e -> code(e, "FORBIDDEN"));
        UUID id =
                csv.upload(maintainer, project, "tasks.csv", source, UUID.randomUUID().toString());
        projects.member(
                maintainer,
                project,
                observer.userId(),
                ProjectRole.MAINTAINER,
                true,
                projects.get(maintainer, project).businessRevision());
        projects.member(
                observer,
                project,
                maintainer.userId(),
                ProjectRole.MEMBER,
                true,
                projects.get(observer, project).businessRevision());
        assertThat(csv.view(maintainer, project, id)).containsEntry("canManage", false);
        assertThatThrownBy(() -> csv.confirm(maintainer, project, id, false))
                .satisfies(e -> code(e, "FORBIDDEN"));
    }

    @Test
    void archiveAndMembershipRevocationDisableOldEditorsAndRequestHandlers() throws Exception {
        accept();
        var request = request();
        projects.member(
                maintainer,
                project,
                assignee.userId(),
                ProjectRole.MEMBER,
                false,
                projects.get(maintainer, project).businessRevision());
        patchTask(assignee, "{\"description\":\"Revoked\"}", revision(), 403);
        assertThatThrownBy(this::request).satisfies(e -> code(e, "FORBIDDEN"));
        var pr = projects.get(maintainer, project);
        collaboration.project(maintainer, project, pr.name(), "", true, pr.businessRevision());
        assertThat(collaboration.detail(creator, project, task.id()))
                .containsEntry("canEditTask", false)
                .containsEntry("canManageDependencies", false)
                .containsEntry("canRequestReassignment", false);
        assertThatThrownBy(
                        () ->
                                reassignments.resolve(
                                        maintainer,
                                        project,
                                        task.id(),
                                        request.id(),
                                        0,
                                        new TaskReassignments.Resolve(
                                                "REJECT", null, "", revision())))
                .satisfies(e -> code(e, "PROJECT_ARCHIVED"));
    }
}
