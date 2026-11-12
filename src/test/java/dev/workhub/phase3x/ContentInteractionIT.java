package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "debug=false", "workhub.bootstrap.login=", "workhub.messaging.enabled=false",
    "workhub.github.enabled=false"
})
@AutoConfigureMockMvc
class ContentInteractionIT {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        CollaborationIT.db(properties);
    }

    @Autowired JdbcTemplate db;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired ContentService content;
    @Autowired CollaborationService collaboration;
    @Autowired MockMvc http;
    ActorContext owner;
    UUID workspace, project;
    Task task;
    String login;

    @BeforeEach
    void fixture() {
        UUID user = UUID.randomUUID();
        owner = new ActorContext(user);
        login = "content_" + user.toString().replace("-", "");
        workspace = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",
                bytes(user), login, "Content reader");
        db.update("INSERT INTO workspace(id,name) VALUES (?,'Content interactions')", bytes(workspace));
        db.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'ADMIN')",
                bytes(workspace), bytes(user));
        project = projects.create(owner, workspace, "Content", key()).id();
        task = newTask(project);
    }

    String key() { return UUID.randomUUID().toString(); }

    Task newTask(UUID projectId) {
        return tasks.create(owner, projectId, new TaskRequests.Create(
                "Task", "Description", TaskStatus.TODO, null, 2, owner.userId(), null), key());
    }

    @Test
    void latestDiscussionAndOlderPagesKeepStableOrderAndLegacyAscendingReads() {
        var created = new ArrayList<ContentRepository.Comment>();
        for (int i = 0; i < 52; i++) created.add(content.createComment(owner, project, task.id(), "Comment " + i, key()));
        // Equal timestamps exercise the ID tie-breaker as well as the normal time cursor.
        db.update("UPDATE comment SET created_at=? WHERE project_id=? AND task_id=?",
                dev.workhub.common.Utc.value(Instant.parse("2026-11-01T00:00:00Z")), bytes(project), bytes(task.id()));
        var ascending = created.stream().map(ContentRepository.Comment::id)
                .sorted(Comparator.comparing(UUID::toString)).toList();
        assertThat(content.comments(owner, project, task.id(), null, 100))
                .extracting(ContentRepository.Comment::id).containsExactlyElementsOf(ascending);
        var latest = content.commentPage(owner, project, task.id(), null, 50, "DESC", null);
        var descending = new ArrayList<>(ascending);Collections.reverse(descending);
        assertThat(latest).extracting(ContentRepository.Comment::id).containsExactlyElementsOf(descending.subList(0, 50));
        var last = latest.get(latest.size() - 1);
        var older = content.commentPage(owner, project, task.id(),
                new TaskRepository.Cursor(last.createdAt(), last.id()), 50, "DESC", null);
        assertThat(older).extracting(ContentRepository.Comment::id).containsExactlyElementsOf(descending.subList(50, 52));
        var detail = collaboration.detail(owner, project, task.id());
        assertThat(detail.get("commentsHasMore")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var visible = (List<ContentRepository.Comment>) detail.get("comments");
        assertThat(visible).extracting(ContentRepository.Comment::id).containsExactlyElementsOf(ascending.subList(2, 52));
    }

    @Test
    void focusedCommentCanSeekAnOldSourceButCannotCrossTaskOrProject() throws Exception {
        var target = content.createComment(owner, project, task.id(), "Old source", key());
        for (int i = 0; i < 51; i++) content.createComment(owner, project, task.id(), "Later " + i, key());
        http.perform(get("/projects/{p}/tasks/{t}/comments", project, task.id()).with(user(login))
                .param("order", "DESC").param("focus", target.id().toString()).param("limit", "51"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(target.id().toString()))
                .andExpect(jsonPath("$.length()").value(1));
        var otherTask = newTask(project);
        http.perform(get("/projects/{p}/tasks/{t}/comments", project, otherTask.id()).with(user(login))
                .param("order", "DESC").param("focus", target.id().toString()))
                .andExpect(status().isNotFound());
        UUID otherProject = projects.create(owner, workspace, "Other", key()).id();
        var taskInOtherProject = newTask(otherProject);
        http.perform(get("/projects/{p}/tasks/{t}/comments", otherProject, taskInOtherProject.id()).with(user(login))
                .param("order", "DESC").param("focus", target.id().toString()))
                .andExpect(status().isNotFound());
        http.perform(get("/projects/{p}/tasks/{t}/comments", project, task.id()).with(user(login))
                .param("order", "ASC").param("focus", target.id().toString()))
                .andExpect(status().isUnprocessableEntity());
        http.perform(get("/projects/{p}/tasks/{t}/comments", project, task.id()).with(user(login))
                .param("order", "DESC; DROP TABLE comment"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void literalDocumentSearchFindsAnOldDocumentBeyondTheFirstHundred() throws Exception {
        var oldest = content.createDocument(owner, project, null, "Release notes 001", "Old background", key());
        for (int i = 2; i <= 101; i++) content.createDocument(owner, project, null,
                "Release notes " + String.format("%03d", i), "Other background", key());
        assertThat(content.documents(owner, project, null, 100)).hasSize(100)
                .extracting(ContentRepository.Document::id).doesNotContain(oldest.id());
        http.perform(get("/projects/{p}/documents", project).with(user(login))
                .param("q", "Release notes 001").param("limit", "21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(oldest.id().toString()));
        http.perform(get("/projects/{p}/documents", project).with(user(login)).param("q", "x".repeat(201)))
                .andExpect(status().isUnprocessableEntity());
        var first = content.documents(owner, project, null, 50);
        var end = first.get(first.size() - 1);
        var second = content.documents(owner, project, new TaskRepository.Cursor(end.updatedAt(), end.id()), 100);
        assertThat(second).hasSize(51).extracting(ContentRepository.Document::id).contains(oldest.id());
        assertThat(first).extracting(ContentRepository.Document::id)
                .doesNotContainAnyElementsOf(second.stream().map(ContentRepository.Document::id).toList());
    }

    @Test
    void assistantAvailabilityIsProjectAuthorizedAndDoesNotExposeConfiguration() throws Exception {
        http.perform(get("/projects/{p}/agent/availability", project)).andExpect(status().isForbidden());
        http.perform(get("/projects/{p}/agent/availability", project).with(user(login)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.reason").value("AGENT_DISABLED"))
                .andExpect(jsonPath("$.length()").value(2));
        http.perform(get("/projects/{p}/agent/availability", UUID.randomUUID()).with(user(login)))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedDocumentTitlesAreIndependentOfListWindowsAndUnlinkKeepsTheDocument() {
        var document = content.createDocument(owner, project, null, "Linked background", "Body", key());
        content.linkDocument(owner, project, task.id(), tasks.get(owner, project, task.id()).revision(), document.id(), true);
        var links = content.links(owner, project, task.id());
        assertThat(links.documents()).containsExactly(document.id());
        assertThat(links.documentDetails()).containsExactly(new ContentRepository.DocumentReference(document.id(), document.title()));
        content.linkDocument(owner, project, task.id(), tasks.get(owner, project, task.id()).revision(), document.id(), false);
        assertThat(content.links(owner, project, task.id()).documents()).isEmpty();
        assertThat(content.getDocument(owner, project, document.id()).body()).isEqualTo("Body");
    }
}
