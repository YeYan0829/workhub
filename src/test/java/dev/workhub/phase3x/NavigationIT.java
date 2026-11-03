package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.workhub.activity.*;
import dev.workhub.identity.*;
import dev.workhub.onboarding.Workspaces;
import dev.workhub.work.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

@SpringBootTest(
        properties = {
            "debug=false",
            "workhub.bootstrap.login=",
            "workhub.messaging.enabled=false",
            "workhub.github.enabled=false"
        })
@AutoConfigureMockMvc
class NavigationIT {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry p) {
        CollaborationIT.db(p);
    }

    @Autowired JdbcTemplate db;
    @Autowired Workspaces spaces;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired CollaborationService collaboration;
    @Autowired ActivityService activity;
    @Autowired ContentService content;
    @Autowired dev.workhub.importing.CsvImports csv;
    @Autowired MockMvc http;
    ActorContext alice, bob;
    UUID w1, w2, p1, p2, hidden;
    String login;
    Task t1, t2;

    @BeforeEach
    void fixture() {
        alice = new ActorContext(UUID.randomUUID());
        bob = new ActorContext(UUID.randomUUID());
        login = "nav" + alice.userId();
        db.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                        + " (?,?,?,'unused'),(?,?,?,'unused')",
                bytes(alice.userId()),
                login,
                "Alice",
                bytes(bob.userId()),
                "nav" + bob.userId(),
                "Bob");
        w1 = spaces.create(alice, "业务团队");
        w2 = spaces.create(bob, "研发团队");
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?),(?,?)",
                bytes(w1),
                bytes(bob.userId()),
                bytes(w2),
                bytes(alice.userId()));
        p1 = project(w1, "同名项目");
        p2 = project(w2, "同名项目");
        hidden = project(w1, "受限项目");
        t1 = task(p1, "我的业务工作");
        t2 = task(p2, "我的研发工作");
        task(hidden, "撤权后必须隐藏");
        projects.member(
                bob,
                hidden,
                alice.userId(),
                ProjectRole.MEMBER,
                false,
                projects.get(bob, hidden).businessRevision());
    }

    UUID project(UUID w, String name) {
        UUID p = projects.create(bob, w, name, UUID.randomUUID().toString()).id();
        projects.member(
                bob,
                p,
                alice.userId(),
                ProjectRole.MEMBER,
                true,
                projects.get(bob, p).businessRevision());
        return p;
    }

    Task task(UUID p, String title) {
        return tasks.create(
                bob,
                p,
                new TaskRequests.Create(
                        title, "", TaskStatus.TODO, TaskType.TASK, 2, alice.userId(), null),
                UUID.randomUUID().toString());
    }

    @Test
    void detailSubscriptionAndImportControlsRespectCurrentActorAndRetention() throws Exception {
        assertThat(collaboration.detail(alice, p1, t1.id()).get("subscribed")).isEqualTo(true);
        content.subscribe(alice, p1, t1.id(), false);
        assertThat(collaboration.detail(alice, p1, t1.id()).get("subscribed")).isEqualTo(false);
        assertThat(collaboration.detail(bob, p1, t1.id()).get("subscribed")).isEqualTo(true);
        content.createComment(alice, p1, t1.id(), "保持明确退订", UUID.randomUUID().toString());
        assertThat(collaboration.detail(alice, p1, t1.id()).get("subscribed")).isEqualTo(false);
        String source =
                "title,description,type,priority,due_date,assignee_login\n报名表校验,,TASK,2,,\n";
        UUID id =
                csv.upload(
                        bob,
                        p1,
                        "campus.csv",
                        source.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        UUID.randomUUID().toString());
        assertThat(csv.view(bob, p1, id))
                .containsEntry("canManage", true)
                .containsEntry("expired", false);
        assertThat(csv.view(alice, p1, id)).containsEntry("canManage", false);
        http.perform(get("/projects/" + p1 + "/imports/" + id).with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].title").value("报名表校验"))
                .andExpect(jsonPath("$.canManage").value(false));
        assertThatThrownBy(() -> csv.confirm(alice, p1, id, false))
                .isInstanceOf(dev.workhub.common.ApiException.class);
        db.update(
                "UPDATE csv_import SET expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)) WHERE id=?",
                bytes(id));
        assertThat(csv.view(bob, p1, id)).containsEntry("expired", true);
        assertThatThrownBy(() -> csv.source(bob, p1, id))
                .isInstanceOf(dev.workhub.common.ApiException.class);
        projects.member(
                bob,
                p1,
                alice.userId(),
                ProjectRole.MEMBER,
                false,
                projects.get(bob, p1).businessRevision());
        assertThatThrownBy(() -> collaboration.detail(alice, p1, t1.id()))
                .isInstanceOf(dev.workhub.common.ApiException.class);
        assertThatThrownBy(() -> csv.view(alice, p1, id))
                .isInstanceOf(dev.workhub.common.ApiException.class);
    }

    @Test
    void personalTasksExposeContextFilterOnServerAndNeverGrantAdminRead() throws Exception {
        http.perform(get("/me/tasks").with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.projects['" + p1 + "'].workspaceName").value("业务团队"))
                .andExpect(jsonPath("$.projects['" + p2 + "'].workspaceName").value("研发团队"));
        http.perform(get("/me/tasks").param("workspaceId", w2.toString()).with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(t2.id().toString()));
        http.perform(
                        get("/me/tasks")
                                .param("workspaceId", w1.toString())
                                .param("projectId", p2.toString())
                                .with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        http.perform(get("/me/tasks").param("projectId", hidden.toString()).with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        http.perform(get("/me/projects").with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        http.perform(get("/me/tasks")).andExpect(status().isForbidden());
        http.perform(get("/me/tasks").param("limit", "101").with(user(login)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void globalInboxCountsAndReadRecheckRecipientAndRevokedWorkspace() throws Exception {
        var inbox = activity.inbox(alice, null, null, false, Long.MAX_VALUE, 50);
        assertThat(inbox.items()).hasSize(2);
        assertThat(inbox.items())
                .extracting(ActivityRepository.InboxItem::workspaceName)
                .containsExactlyInAnyOrder("业务团队", "研发团队");
        assertThat(activity.unreadCount(alice)).isEqualTo(2);
        long id =
                inbox.items().stream()
                        .filter(n -> n.projectId().equals(p1))
                        .findFirst()
                        .orElseThrow()
                        .id();
        http.perform(
                        post("/me/notifications/" + id + "/read")
                                .with(user("nav" + bob.userId()))
                                .with(csrf()))
                .andExpect(status().isNotFound());
        http.perform(post("/me/notifications/" + id + "/read").with(user(login)))
                .andExpect(status().isForbidden());
        http.perform(post("/me/notifications/" + id + "/read").with(user(login)).with(csrf()))
                .andExpect(status().isNoContent());
        http.perform(post("/me/notifications/" + id + "/read").with(user(login)).with(csrf()))
                .andExpect(status().isNoContent());
        http.perform(get("/me/notifications/unread-count").with(user(login)))
                .andExpect(jsonPath("$.count").value(1));
        spaces.membership(bob, w2, alice.userId(), "MEMBER", false);
        http.perform(get("/me/notifications").with(user(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        assertThat(activity.unreadCount(alice)).isZero();
        long revoked =
                inbox.items().stream()
                        .filter(n -> n.projectId().equals(p2))
                        .findFirst()
                        .orElseThrow()
                        .id();
        http.perform(post("/me/notifications/" + revoked + "/read").with(user(login)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(collaboration.query(alice, null, null, null, null, null, null, 0, 50).items())
                .extracting(TaskResponse::id)
                .containsExactly(t1.id());
    }

    @Test
    void inboxCursorUnreadAndArchiveRemainConsistent() throws Exception {
        for (int i = 0; i < 4; i++) task(p1, "分页 " + i);
        var first = activity.inbox(alice, null, null, false, Long.MAX_VALUE, 2);
        task(p1, "翻页时新到达");
        var second = activity.inbox(alice, null, null, false, first.nextBefore(), 2);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items())
                .extracting(ActivityRepository.InboxItem::id)
                .doesNotContainAnyElementsOf(
                        first.items().stream().map(ActivityRepository.InboxItem::id).toList());
        collaboration.project(bob, p1, "同名项目", "", true, projects.get(bob, p1).businessRevision());
        var scoped = activity.inbox(alice, w1, p1, false, Long.MAX_VALUE, 50);
        assertThat(scoped.items()).hasSize(6).allMatch(ActivityRepository.InboxItem::archived);
        activity.markRead(alice, scoped.items().get(0).id());
        assertThat(activity.inbox(alice, w1, p1, true, Long.MAX_VALUE, 50).items()).hasSize(5);
        http.perform(get("/me/notifications").param("before", "0").with(user(login)))
                .andExpect(status().isUnprocessableEntity());
    }
}
