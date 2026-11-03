package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ApiIT extends Phase1Support {
    @Test
    void wrongLoginCredentialsHaveDedicatedErrorWithoutRevealingAccountExistence()
            throws Exception {
        for (String username : List.of(login, "missing-" + UUID.randomUUID()))
            http.perform(
                            post("/session/login")
                                    .with(csrf())
                                    .param("username", username)
                                    .param("password", "incorrect-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("登录名或密码错误，请重新输入。"));
    }

    @Test
    void githubSetupIsMaintainerOnlyAndReturnsNoCredentialDetails() throws Exception {
        String path = "/projects/" + project + "/github/setup";
        String body =
                http.perform(get(path).with(user(login)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.readEnabled").isBoolean())
                        .andExpect(jsonPath("$.writeEnabled").isBoolean())
                        .andExpect(jsonPath("$.authorizedRepositories").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("private-key", "webhook-secret", "token", "app-id");
        http.perform(get(path).with(user("u" + other.userId()))).andExpect(status().isForbidden());
        http.perform(get("/projects/" + UUID.randomUUID() + "/github/setup").with(user(login)))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiTaskVersionsErrorsScopeWhitelistAndCsrf() throws Exception {
        Task task = task("original");
        String path = "/projects/" + project + "/tasks/" + task.id();
        http.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        http.perform(get(path).with(user(login)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        http.perform(
                        patch(path)
                                .with(user(login))
                                .contentType("application/json")
                                .content("{\"title\":\"new\"}"))
                .andExpect(status().isForbidden());
        http.perform(
                        patch(path)
                                .with(user(login))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"title\":\"new\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        http.perform(
                        patch(path)
                                .with(user(login))
                                .with(csrf())
                                .header("If-Match", "0")
                                .contentType("application/json")
                                .content("{\"title\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        http.perform(
                        patch(path)
                                .with(user(login))
                                .with(csrf())
                                .header("If-Match", "0")
                                .contentType("application/json")
                                .content("{\"title\":\"stale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(1))
                .andExpect(jsonPath("$.refreshRequired").value(true))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        for (String body :
                List.of(
                        "{\"isBlocked\":false}",
                        "{\"status\":\"BLOCKED\"}",
                        "{\"title\":null}",
                        "{\"priority\":9}"))
            http.perform(
                            patch(path)
                                    .with(user(login))
                                    .with(csrf())
                                    .header("If-Match", "1")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity());
        http.perform(
                        get("/projects/" + UUID.randomUUID() + "/tasks/" + task.id())
                                .with(user(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.currentRevision").isEmpty());
        http.perform(get("/projects/" + project + "/tasks/" + UUID.randomUUID()).with(user(login)))
                .andExpect(status().isNotFound());
        http.perform(get("/projects/" + project + "/tasks?limit=101").with(user(login)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void apiDependencyCycleIncludesWitnessAndRevisionPrecondition() throws Exception {
        Task a = task("A"), b = task("B");
        String path = "/projects/" + project + "/dependencies";
        String first =
                "{\"blockerId\":\""
                        + a.id()
                        + "\",\"blockedId\":\""
                        + b.id()
                        + "\",\"expectedBusinessRevision\":"
                        + revision()
                        + "}";
        http.perform(
                        post(path)
                                .with(user(login))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(first))
                .andExpect(status().isCreated());
        String reverse =
                "{\"blockerId\":\""
                        + b.id()
                        + "\",\"blockedId\":\""
                        + a.id()
                        + "\",\"expectedBusinessRevision\":"
                        + revision()
                        + "}";
        http.perform(
                        post(path)
                                .with(user(login))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(reverse))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"))
                .andExpect(jsonPath("$.path.length()").value(3));
    }

    @Test
    void memberAndWorkspaceRevocationAndTriageAuthorization() {
        Task triage = task("triage", TaskStatus.TRIAGE, other.userId());
        assertThatThrownBy(() -> tasks.acceptTriage(other, project, triage.id(), 0, null, null))
                .satisfies(e -> expectCode(e, "FORBIDDEN"));
        assertThat(tasks.acceptTriage(actor, project, triage.id(), 0, TaskType.BUG, 1).status())
                .isEqualTo(TaskStatus.TODO);
        jdbc.update(
                "UPDATE workspace_member SET active=false WHERE workspace_id=? AND user_id=?",
                bytes(workspace),
                bytes(other.userId()));
        assertThatThrownBy(() -> tasks.get(other, project, triage.id()))
                .satisfies(e -> expectCode(e, "FORBIDDEN"));
        var patch = new TaskRequests.Patch();
        patch.setAssigneeId(other.userId());
        assertThatThrownBy(() -> tasks.update(actor, project, triage.id(), 1, patch))
                .satisfies(e -> expectCode(e, "VALIDATION_ERROR"));
    }

    @Test
    void githubSharedFieldGuardIsAtomicWithoutStartingIntegration() {
        Task task = task("mirror");
        jdbc.update("UPDATE task SET origin='GITHUB' WHERE id=?", bytes(task.id()));
        var patch = new TaskRequests.Patch();
        patch.setTitle("remote");
        patch.setPriority(0);
        var before = counts();
        assertThatThrownBy(() -> tasks.update(actor, project, task.id(), 0, patch))
                .satisfies(e -> expectCode(e, "EXTERNAL_OPERATION_REQUIRED"));
        assertThat(counts()).isEqualTo(before);
        assertThat(tasks.get(actor, project, task.id()).priority()).isEqualTo(2);
    }
}
