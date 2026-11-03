package dev.workhub.identity;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Ids;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireTriageTask(UUID project, UUID task) {
        if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM task WHERE project_id=? AND id=?"
                + " AND origin IN ('MANUAL','AGENT','GITHUB') AND status='TRIAGE' AND triage_required=true)",
                Boolean.class, bytes(project), bytes(task)))
            throw new dev.workhub.common.ApiException(409, "TRIAGE_NO_LONGER_REQUIRED", "任务不存在或已不再待分诊，分析停止");
    }

    public record User(
            UUID id, String login, String displayName, String passwordHash, boolean active) {}

    public Optional<User> findByLogin(String login) {
        return jdbc
                .query(
                        "SELECT * FROM app_user WHERE login=?",
                        (r, n) ->
                                new User(
                                        Ids.read(r, "id"),
                                        r.getString("login"),
                                        r.getString("display_name"),
                                        r.getString("password_hash"),
                                        r.getBoolean("active")),
                        login)
                .stream()
                .findFirst();
    }

    public void lockWorkspace(UUID workspace) {
        jdbc.queryForList("SELECT id FROM workspace WHERE id=? FOR SHARE", bytes(workspace));
    }

    public boolean activeUser(UUID user, boolean lock) {
        return jdbc
                .query(
                        "SELECT active FROM app_user WHERE id=?" + (lock ? " FOR SHARE" : ""),
                        (r, n) -> r.getBoolean(1),
                        bytes(user))
                .stream()
                .findFirst()
                .orElse(false);
    }

    public boolean activeWorkspaceMember(UUID workspace, UUID user, boolean lock) {
        return jdbc
                .query(
                        "SELECT active FROM workspace_member WHERE workspace_id=? AND user_id=?"
                                + (lock ? " FOR SHARE" : ""),
                        (r, n) -> r.getBoolean(1),
                        bytes(workspace),
                        bytes(user))
                .stream()
                .findFirst()
                .orElse(false);
    }

    public boolean activeProjectMember(UUID project, UUID user) {
        return jdbc.queryForObject(
                """
SELECT EXISTS(SELECT 1 FROM project_member pm JOIN project p ON p.id=pm.project_id
JOIN workspace_member wm ON wm.workspace_id=p.workspace_id AND wm.user_id=pm.user_id
JOIN app_user u ON u.id=pm.user_id
WHERE pm.project_id=? AND pm.user_id=? AND pm.active AND wm.active AND u.active)
""",
                Boolean.class,
                bytes(project),
                bytes(user));
    }

    public Optional<ProjectRole> role(UUID project, UUID user) {
        return jdbc
                .query(
                        "SELECT role FROM project_member WHERE project_id=? AND user_id=? AND"
                                + " active",
                        (r, n) -> ProjectRole.valueOf(r.getString(1)),
                        bytes(project),
                        bytes(user))
                .stream()
                .findFirst();
    }

    public List<UUID> maintainers(UUID project) {
        return jdbc.query(
                "SELECT user_id FROM project_member WHERE project_id=? AND active AND"
                    + " role='MAINTAINER'",
                (r, n) -> read(r, "user_id"),
                bytes(project));
    }

    public record Membership(ProjectRole role, boolean active) {}

    public Optional<Membership> membership(UUID project, UUID user) {
        return jdbc
                .query(
                        "SELECT role,active FROM project_member WHERE project_id=? AND user_id=?",
                        (r, n) ->
                                new Membership(
                                        ProjectRole.valueOf(r.getString(1)), r.getBoolean(2)),
                        bytes(project),
                        bytes(user))
                .stream()
                .findFirst();
    }

    public record WorkspaceView(UUID id, String name) {}

    public List<WorkspaceView> workspaces(UUID user) {
        return jdbc.query(
                "SELECT w.id,w.name FROM workspace w JOIN workspace_member m ON m.workspace_id=w.id"
                        + " WHERE m.user_id=? AND m.active",
                (r, n) -> new WorkspaceView(Ids.read(r, "id"), r.getString("name")),
                bytes(user));
    }

    public void saveMembership(UUID project, UUID user, ProjectRole role, boolean active) {
        jdbc.update(
                """
                INSERT INTO project_member(project_id,user_id,role,active) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE role=VALUES(role),active=VALUES(active),revision=revision+1
                """,
                bytes(project),
                bytes(user),
                role.name(),
                active);
    }

    public void lockCreator(UUID user) {
        jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", bytes(user));
    }

    public List<UUID> activeMentions(UUID project, String login) {
        return jdbc.query(
                "SELECT u.id FROM app_user u JOIN project_member m ON m.user_id=u.id WHERE"
                        + " m.project_id=? AND u.login=? AND m.active AND u.active",
                (r, n) -> uuid(r.getBytes(1)),
                bytes(project),
                login);
    }
}
