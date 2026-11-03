package dev.workhub.onboarding;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class Workspaces {
    private final JdbcTemplate db;
    private final IdentityRepository identities;
    private final MailService mail;
    private final Json json;
    private final RateLimits rate;

    public Workspaces(
            JdbcTemplate db,
            IdentityRepository identities,
            MailService mail,
            Json json,
            RateLimits rate) {
        this.db = db;
        this.identities = identities;
        this.mail = mail;
        this.json = json;
        this.rate = rate;
    }

    public void lock(UUID w) {
        if (db.queryForList("SELECT id FROM workspace WHERE id=? FOR UPDATE", bytes(w)).isEmpty())
            throw ApiException.forbidden();
    }

    public void member(ActorContext a, UUID w) {
        if (!identities.activeUser(a.userId(), false)
                || !identities.activeWorkspaceMember(w, a.userId(), false))
            throw ApiException.forbidden();
    }

    public void admin(ActorContext a, UUID w) {
        member(a, w);
        if (db.queryForObject(
                        "SELECT role FROM workspace_member WHERE workspace_id=? AND user_id=?",
                        String.class,
                        bytes(w),
                        bytes(a.userId()))
                .equals("ADMIN")) return;
        throw ApiException.forbidden();
    }

    public void audit(ActorContext a, UUID w, UUID p, String kind, Object detail) {
        db.update(
                "INSERT INTO management_activity(workspace_id,project_id,actor_id,kind,detail)"
                        + " VALUES (?,?,?,?,?)",
                bytes(w),
                bytes(p),
                bytes(a.userId()),
                kind,
                json.write(detail));
    }

    @Transactional
    public UUID create(ActorContext a, String name) {
        Inputs.text(name, "name", 200);
        if (!identities.activeUser(a.userId(), true)) throw ApiException.forbidden();
        UUID w = UUID.randomUUID();
        db.update("INSERT INTO workspace(id,name) VALUES (?,?)", bytes(w), name);
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'ADMIN')",
                bytes(w),
                bytes(a.userId()));
        audit(a, w, null, "WORKSPACE_CREATED", Map.of("name", name));
        return w;
    }

    public Map<String, Object> get(ActorContext a, UUID w) {
        member(a, w);
        return db.queryForMap("SELECT name,revision FROM workspace WHERE id=?", bytes(w));
    }

    @Transactional
    public void rename(ActorContext a, UUID w, String name, long revision) {
        lock(w);
        admin(a, w);
        Inputs.text(name, "name", 200);
        if (db.update(
                        "UPDATE workspace SET name=?,revision=revision+1 WHERE id=? AND revision=?",
                        name,
                        bytes(w),
                        revision)
                != 1)
            throw ApiException.conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    ((Number) get(a, w).get("revision")).longValue());
        audit(a, w, null, "WORKSPACE_RENAMED", Map.of("name", name));
    }

    public record Member(
            UUID id, String login, String displayName, String email, String role, boolean active) {}

    public List<Member> members(ActorContext a, UUID w) {
        member(a, w);
        return db.query(
                "SELECT u.id,u.login,u.display_name,u.email,m.role,m.active FROM workspace_member m"
                        + " JOIN app_user u ON u.id=m.user_id WHERE workspace_id=? ORDER BY u.login"
                        + " LIMIT 1000",
                (r, n) ->
                        new Member(
                                read(r, "id"),
                                r.getString("login"),
                                r.getString("display_name"),
                                r.getString("email"),
                                r.getString("role"),
                                r.getBoolean("active")),
                bytes(w));
    }

    public List<Map<String, Object>> invitations(ActorContext a, UUID w) {
        admin(a, w);
        return db.queryForList(
                "SELECT BIN_TO_UUID(id) id,email,IF(status='PENDING' AND"
                    + " expires_at<UTC_TIMESTAMP(6),'EXPIRED',status) status,expires_at expiresAt"
                    + " FROM workspace_invitation WHERE workspace_id=? ORDER BY created_at DESC"
                    + " LIMIT 100",
                bytes(w));
    }

    @Transactional
    public UUID invite(ActorContext a, UUID w, String email) {
        lock(w);
        admin(a, w);
        String e = Accounts.email(email);
        rate.require("invite/" + w, 30);
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM workspace_member m JOIN app_user u ON u.id=m.user_id"
                                + " WHERE m.workspace_id=? AND m.active AND u.email=?",
                        Integer.class,
                        bytes(w),
                        e)
                > 0) throw new ApiException(409, "ALREADY_MEMBER", "该邮箱已经是团队成员");
        var existing =
                db.query(
                        "SELECT id FROM workspace_invitation WHERE workspace_id=? AND email=? AND"
                                + " status='PENDING' AND expires_at>UTC_TIMESTAMP(6)",
                        (r, n) -> read(r, "id"),
                        bytes(w),
                        e);
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = UUID.randomUUID();
        String token = Accounts.token();
        db.update(
                "INSERT INTO"
                    + " workspace_invitation(id,workspace_id,email,token_hash,invited_by,expires_at)"
                    + " VALUES (?,?,?,?,?,TIMESTAMPADD(DAY,7,UTC_TIMESTAMP(6)))",
                bytes(id),
                bytes(w),
                e,
                Inputs.hash(token),
                bytes(a.userId()));
        mail.send(e, "invite", token);
        audit(a, w, null, "MEMBER_INVITED", Map.of("invitation", id, "email", e));
        return id;
    }

    @Transactional
    public void revoke(ActorContext a, UUID w, UUID id) {
        lock(w);
        admin(a, w);
        db.update(
                "UPDATE workspace_invitation SET status='REVOKED' WHERE workspace_id=? AND id=? AND"
                        + " status='PENDING'",
                bytes(w),
                bytes(id));
        audit(a, w, null, "INVITATION_REVOKED", Map.of("invitation", id));
    }

    public record InvitationView(
            String workspaceName, String email, String status, String accountState) {}

    public InvitationView preview(String token) {
        if (token == null || token.isBlank() || token.length() > 100)
            throw ApiException.invalid("无效邀请链接");
        var rows =
                db.query(
                        "SELECT w.name,i.email,IF(i.status='PENDING' AND"
                            + " i.expires_at<=UTC_TIMESTAMP(6),'EXPIRED',i.status) status, CASE"
                            + " WHEN u.id IS NULL THEN 'REGISTER' WHEN NOT u.email_verified THEN"
                            + " 'VERIFY' ELSE 'LOGIN' END account_state FROM workspace_invitation i"
                            + " JOIN workspace w ON w.id=i.workspace_id LEFT JOIN app_user u ON"
                            + " u.email=i.email WHERE i.token_hash=?",
                        (r, n) ->
                                new InvitationView(
                                        r.getString("name"),
                                        r.getString("email"),
                                        r.getString("status"),
                                        r.getString("account_state")),
                        Inputs.hash(token));
        if (rows.isEmpty())
            throw new ApiException(410, "INVITATION_UNAVAILABLE", "邀请链接无效，请联系管理员重新邀请");
        return rows.get(0);
    }

    public List<Map<String, Object>> pendingInvitations(ActorContext a) {
        return db.queryForList(
                "SELECT BIN_TO_UUID(i.id) id,w.name workspaceName,i.email,i.expires_at expiresAt"
                        + " FROM workspace_invitation i JOIN workspace w ON w.id=i.workspace_id"
                        + " JOIN app_user u ON u.email=i.email WHERE u.id=? AND u.active"
                        + " AND u.email_verified AND i.status='PENDING'"
                        + " AND i.expires_at>UTC_TIMESTAMP(6) ORDER BY i.created_at DESC LIMIT 100",
                bytes(a.userId()));
    }

    @Transactional
    public UUID acceptInvitation(ActorContext a, UUID invitation) {
        var hashes =
                db.queryForList(
                        "SELECT token_hash FROM workspace_invitation WHERE id=?",
                        String.class,
                        bytes(invitation));
        if (hashes.isEmpty()) throw ApiException.missing();
        // The shared acceptance path still checks the current verified email under the workspace
        // lock.
        return acceptHash(a, hashes.get(0));
    }

    @Transactional
    public UUID accept(ActorContext a, String token) {
        if (token == null || token.length() > 100) throw ApiException.invalid("无效邀请");
        return acceptHash(a, Inputs.hash(token));
    }

    private UUID acceptHash(ActorContext a, String hash) {
        var ws =
                db.query(
                        "SELECT workspace_id FROM workspace_invitation WHERE token_hash=?",
                        (r, n) -> read(r, "workspace_id"),
                        hash);
        if (ws.isEmpty()) throw ApiException.missing();
        UUID w = ws.get(0);
        lock(w);
        var invitation =
                db.queryForMap(
                        "SELECT *,expires_at>UTC_TIMESTAMP(6) valid FROM workspace_invitation WHERE"
                                + " token_hash=? FOR UPDATE",
                        hash);
        var user =
                db.queryForMap(
                        "SELECT email,email_verified,active FROM app_user WHERE id=?",
                        bytes(a.userId()));
        if (!Objects.equals(user.get("email"), invitation.get("email")))
            throw new ApiException(403, "INVITATION_ACCOUNT_MISMATCH", "当前账号邮箱与邀请收件人不一致，请切换账号");
        if (!Boolean.TRUE.equals(user.get("active"))
                || !Boolean.TRUE.equals(user.get("email_verified")))
            throw new ApiException(403, "INVITATION_EMAIL_UNVERIFIED", "请先验证收件邮箱，再接受邀请");
        if (invitation.get("status").equals("ACCEPTED")
                && a.userId().equals(uuid((byte[]) invitation.get("accepted_by")))) {
            if (!identities.activeWorkspaceMember(w, a.userId(), false))
                throw new ApiException(410, "INVITATION_MEMBERSHIP_ENDED", "你已不在这个团队中，请管理员发送新邀请");
            return w;
        }
        if (!invitation.get("status").equals("PENDING")
                || ((Number) invitation.get("valid")).intValue() != 1)
            throw new ApiException(410, "INVITATION_UNAVAILABLE", "邀请已撤回或过期，请联系管理员重新邀请");
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'MEMBER') ON"
                        + " DUPLICATE KEY UPDATE"
                        + " role=IF(active,role,'MEMBER'),active=TRUE,revision=revision+1",
                bytes(w),
                bytes(a.userId()));
        db.update(
                "UPDATE workspace_invitation SET status='ACCEPTED',accepted_by=? WHERE"
                        + " token_hash=?",
                bytes(a.userId()),
                hash);
        audit(a, w, null, "INVITATION_ACCEPTED", Map.of("user", a.userId()));
        return w;
    }

    @Transactional
    public void membership(ActorContext a, UUID w, UUID user, String role, boolean active) {
        lock(w);
        member(a, w);
        if (!a.userId().equals(user) || active) admin(a, w);
        if (!Set.of("ADMIN", "MEMBER").contains(role)) throw ApiException.invalid("Invalid role");
        var old =
                db.queryForMap(
                        "SELECT role,active FROM workspace_member WHERE workspace_id=? AND"
                                + " user_id=?",
                        bytes(w),
                        bytes(user));
        if (Boolean.TRUE.equals(old.get("active"))
                && old.get("role").equals("ADMIN")
                && (!active || !role.equals("ADMIN"))
                && db.queryForObject(
                                "SELECT COUNT(*) FROM workspace_member m JOIN app_user u ON"
                                    + " u.id=m.user_id WHERE m.workspace_id=? AND m.role='ADMIN'"
                                    + " AND m.active AND u.active",
                                Integer.class,
                                bytes(w))
                        <= 1) throw new ApiException(409, "LAST_ADMIN", "请先授予另一位成员管理员角色");
        if (!active) {
            var projects =
                    db.query(
                            "SELECT p.id FROM project p JOIN project_member m ON m.project_id=p.id"
                                + " WHERE p.workspace_id=? AND m.user_id=? AND m.active ORDER BY"
                                + " p.id",
                            (r, n) -> read(r, "id"),
                            bytes(w),
                            bytes(user));
            for (UUID p : projects) {
                db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", bytes(p));
                lastMaintainer(p, user);
            }
            db.update(
                    "UPDATE project_member m JOIN project p ON p.id=m.project_id SET"
                            + " m.active=FALSE,m.role='MEMBER',m.revision=m.revision+1 WHERE"
                            + " p.workspace_id=? AND m.user_id=?",
                    bytes(w),
                    bytes(user));
        } else if (!Boolean.TRUE.equals(old.get("active")))
            throw new ApiException(409, "INVITATION_REQUIRED", "重新加入需要接受新邀请");
        db.update(
                "UPDATE workspace_member SET role=?,active=?,revision=revision+1 WHERE"
                        + " workspace_id=? AND user_id=?",
                active ? role : "MEMBER",
                active,
                bytes(w),
                bytes(user));
        audit(
                a,
                w,
                null,
                "MEMBERSHIP_CHANGED",
                Map.of("user", user, "role", role, "active", active));
    }

    public void lastMaintainer(UUID p, UUID user) {
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM project_member WHERE project_id=? AND user_id=? AND"
                                + " active AND role='MAINTAINER'",
                        Integer.class,
                        bytes(p),
                        bytes(user))
                == 0) return;
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM project_member pm JOIN project p ON"
                            + " p.id=pm.project_id JOIN workspace_member wm ON"
                            + " wm.workspace_id=p.workspace_id AND wm.user_id=pm.user_id JOIN"
                            + " app_user u ON u.id=pm.user_id WHERE pm.project_id=? AND"
                            + " pm.role='MAINTAINER' AND pm.active AND wm.active AND u.active AND"
                            + " pm.user_id<>?",
                        Integer.class,
                        bytes(p),
                        bytes(user))
                == 0) throw new ApiException(409, "LAST_MAINTAINER", "请先交接项目维护者，再退出、移除或降权");
    }

    public List<Map<String, Object>> projects(ActorContext a, UUID w) {
        admin(a, w);
        return db.queryForList(
                "SELECT BIN_TO_UUID(id) id,name,archived FROM project WHERE workspace_id=? ORDER BY"
                        + " id LIMIT 1000",
                bytes(w));
    }

    @Transactional
    public void handover(ActorContext a, UUID w, UUID p, UUID user) {
        lock(w);
        admin(a, w);
        if (db.queryForList(
                        "SELECT id FROM project WHERE id=? AND workspace_id=? FOR UPDATE",
                        bytes(p),
                        bytes(w))
                .isEmpty()) throw ApiException.missing();
        if (!identities.activeUser(user, false)
                || !identities.activeWorkspaceMember(w, user, false))
            throw ApiException.invalid("需要有效的团队成员");
        identities.saveMembership(p, user, ProjectRole.MAINTAINER, true);
        db.update("UPDATE project SET business_revision=business_revision+1 WHERE id=?", bytes(p));
        audit(a, w, p, "MAINTAINER_HANDOVER", Map.of("newMaintainer", user));
    }

    public List<Map<String, Object>> audit(ActorContext a, UUID w) {
        admin(a, w);
        return db.queryForList(
                "SELECT id,BIN_TO_UUID(actor_id) actorId,BIN_TO_UUID(project_id)"
                    + " projectId,kind,detail,created_at createdAt FROM management_activity WHERE"
                    + " workspace_id=? ORDER BY id DESC LIMIT 100",
                bytes(w));
    }
}
