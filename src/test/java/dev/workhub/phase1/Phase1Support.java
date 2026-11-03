package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import dev.workhub.activity.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.sql.DataSource;

@SpringBootTest(
        properties = {
            "debug=false",
            "workhub.bootstrap.login=",
            "server.servlet.session.cookie.secure=false"
        })
@AutoConfigureMockMvc
abstract class Phase1Support {
    static final java.nio.file.Path EVIDENCE =
            java.nio.file.Path.of(System.getProperty("workhub.test.evidence", "target/evidence/phase2") + "/regression-" + UUID.randomUUID());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", SchemaIT::url);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 24);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired DependencyService dag;
    @Autowired DependencyRepository edges;
    @Autowired ActivityService activity;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc http;
    ActorContext actor, other;
    UUID workspace, project;
    String login;

    @BeforeEach
    void fixture() {
        actor = new ActorContext(UUID.randomUUID());
        other = new ActorContext(UUID.randomUUID());
        workspace = UUID.randomUUID();
        login = "u" + actor.userId();
        jdbc.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                        + " (?,?,?,'unused'),(?,?,?,'unused')",
                bytes(actor.userId()),
                login,
                "Alice",
                bytes(other.userId()),
                "u" + other.userId(),
                "Bob");
        jdbc.update("INSERT INTO workspace(id,name) VALUES (?,'fixture')", bytes(workspace));
        jdbc.update(
                "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?),(?,?)",
                bytes(workspace),
                bytes(actor.userId()),
                bytes(workspace),
                bytes(other.userId()));
        project = projects.create(actor, workspace, "Phase 1", UUID.randomUUID().toString()).id();
        projects.member(actor, project, other.userId(), ProjectRole.MEMBER, true, revision());
    }

    long revision() {
        return projects.get(actor, project).businessRevision();
    }

    Task task(String title) {
        return task(title, TaskStatus.TODO, null);
    }

    Task task(String title, TaskStatus status, UUID assignee) {
        return tasks.create(
                actor,
                project,
                new TaskRequests.Create(title, "", status, TaskType.TASK, 2, assignee, null),
                UUID.randomUUID().toString());
    }

    void add(Task a, Task b) {
        dag.add(
                actor,
                project,
                revision(),
                new Dependency(a.id(), b.id()),
                UUID.randomUUID().toString());
    }

    Task state(Task task, TaskStatus status) {
        Task now = tasks.get(actor, project, task.id());
        var patch = new TaskRequests.Patch();
        patch.setStatus(status);
        return tasks.update(actor, project, task.id(), now.revision(), patch);
    }

    Map<String, Long> counts() {
        Map<String, Long> result = new TreeMap<>();
        for (String table :
                List.of(
                        "task",
                        "task_dependency",
                        "activity",
                        "notification",
                        "knowledge_source",
                        "work_job",
                        "operation_receipt",
                        "comment",
                        "project_document"))
            result.put(
                    table,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE project_id=?",
                            Long.class,
                            bytes(project)));
        result.put(
                "outbox",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_message o JOIN work_job j ON j.id=o.job_id"
                                + " WHERE j.project_id=?",
                        Long.class,
                        bytes(project)));
        result.put("revision", revision());
        return result;
    }

    TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        return tx;
    }

    static void expectCode(Throwable e, String code) {
        org.assertj.core.api.Assertions.assertThat(e).isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThat(((ApiException) e).code).isEqualTo(code);
    }
}
