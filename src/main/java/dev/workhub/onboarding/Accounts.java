package dev.workhub.onboarding;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class Accounts {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final RateLimits rate;

    public Accounts(JdbcTemplate db, PasswordEncoder encoder, MailService mail, RateLimits rate) {
        this.db = db;
        this.encoder = encoder;
        this.mail = mail;
        this.rate = rate;
    }

    public static String email(String e) {
        if (e == null || e.length() > 254 || !e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw ApiException.invalid("请输入有效邮箱");
        return e.trim().toLowerCase(Locale.ROOT);
    }

    public static void password(String p) {
        if (p == null
                || p.length() < 12
                || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw ApiException.invalid("密码至少12个字符，UTF-8不超过72字节");
    }

    public static String token() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Transactional
    public void register(String login, String email, String name, String password) {
        register(login, email, name, password, null);
    }

    @Transactional
    public void register(
            String login, String email, String name, String password, String invitationToken) {
        String e = email(email);
        validateInvitation(e, invitationToken);
        rate.require("register/" + e, 5);
        password(password);
        if (login == null || !login.matches("[A-Za-z0-9_-]{3,60}"))
            throw ApiException.invalid("登录名使用3–60位字母数字、下划线或短横线");
        Inputs.text(name, "displayName", 200);
        UUID id = UUID.randomUUID();
        try {
            db.update(
                    "INSERT INTO"
                        + " app_user(id,login,display_name,password_hash,email,email_verified,active)"
                        + " VALUES (?,?,?,?,?,FALSE,FALSE)",
                    bytes(id),
                    login,
                    name,
                    encoder.encode(password),
                    e);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(409, "ACCOUNT_EXISTS", "登录名或邮箱已被使用，请登录或重置密码");
        }
        issue(id, e, "verify", 1440, invitationToken);
    }

    private void validateInvitation(String email, String token) {
        if (token == null) return;
        if (token.isBlank()
                || token.length() > 100
                || db.queryForObject(
                                "SELECT COUNT(*) FROM workspace_invitation WHERE token_hash=? AND"
                                    + " email=? AND status='PENDING' AND"
                                    + " expires_at>UTC_TIMESTAMP(6)",
                                Integer.class,
                                Inputs.hash(token),
                                email)
                        != 1)
            throw new ApiException(410, "INVITATION_UNAVAILABLE", "邀请已失效或与邮箱不匹配，请重新打开邀请链接");
    }

    private void issue(UUID id, String e, String purpose, int minutes, String invitationToken) {
        String token = token();
        db.update(
                "UPDATE account_token SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND purpose=?"
                        + " AND used_at IS NULL",
                bytes(id),
                purpose);
        db.update(
                "INSERT INTO account_token(token_hash,user_id,purpose,expires_at) VALUES"
                        + " (?,?,?,TIMESTAMPADD(MINUTE,?,UTC_TIMESTAMP(6)))",
                Inputs.hash(token),
                bytes(id),
                purpose,
                minutes);
        mail.send(e, purpose, token, invitationToken);
    }

    @Transactional
    public void request(String email, String purpose) {
        request(email, purpose, null);
    }

    @Transactional
    public void request(String email, String purpose, String invitationToken) {
        String e = email(email);
        validateInvitation(e, invitationToken);
        rate.require(purpose + "/" + e, 5);
        var rows = db.queryForList("SELECT id,email_verified FROM app_user WHERE email=?", e);
        if (rows.isEmpty()) return;
        var r = rows.get(0);
        boolean verified = (Boolean) r.get("email_verified");
        if (purpose.equals("reset") && !verified || purpose.equals("verify") && verified) return;
        db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", r.get("id"));
        issue(
                uuid((byte[]) r.get("id")),
                e,
                purpose,
                purpose.equals("reset") ? 30 : 1440,
                invitationToken);
    }

    @Transactional
    public void consume(String token, String purpose, String password) {
        if (token == null || token.length() > 100) throw ApiException.invalid("无效链接");
        // Always lock the user before tokens, matching issuance and password changes.
        var users =
                db.query(
                        "SELECT user_id FROM account_token WHERE token_hash=?",
                        (r, n) -> read(r, "user_id"),
                        Inputs.hash(token));
        if (users.isEmpty()) throw new ApiException(410, "TOKEN_EXPIRED", "链接无效或已过期，请重新申请");
        UUID user = users.get(0);
        db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", bytes(user));
        var rows =
                db.queryForList(
                        "SELECT user_id FROM account_token WHERE token_hash=? AND purpose=? AND"
                                + " used_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",
                        Inputs.hash(token),
                        purpose);
        if (rows.isEmpty()) throw new ApiException(410, "TOKEN_EXPIRED", "链接已使用或过期，请重新申请");
        if (purpose.equals("verify"))
            db.update(
                    "UPDATE app_user SET email_verified=TRUE,active=TRUE WHERE id=?", bytes(user));
        else {
            password(password);
            db.update(
                    "UPDATE app_user SET password_hash=?,auth_revision=auth_revision+1 WHERE id=?",
                    encoder.encode(password),
                    bytes(user));
            invalidate(user);
        }
        db.update(
                "UPDATE account_token SET used_at=UTC_TIMESTAMP(6) WHERE token_hash=?",
                Inputs.hash(token));
    }

    public Map<String, Object> profile(ActorContext a) {
        return db.queryForMap(
                "SELECT login,display_name AS displayName,email,email_verified AS emailVerified"
                        + " FROM app_user WHERE id=? AND active",
                bytes(a.userId()));
    }

    @Transactional
    public void profile(ActorContext a, String name) {
        Inputs.text(name, "displayName", 200);
        db.update(
                "UPDATE app_user SET display_name=? WHERE id=? AND active",
                name,
                bytes(a.userId()));
    }

    @Transactional
    public void changePassword(ActorContext a, String old, String next) {
        password(next);
        var hash =
                db.queryForObject(
                        "SELECT password_hash FROM app_user WHERE id=? FOR UPDATE",
                        String.class,
                        bytes(a.userId()));
        rate.require("password/" + a.userId(), 10);
        if (old == null || !encoder.matches(old, hash))
            throw new ApiException(403, "PASSWORD_MISMATCH", "原密码不正确");
        db.update(
                "UPDATE app_user SET password_hash=?,auth_revision=auth_revision+1 WHERE id=?",
                encoder.encode(next),
                bytes(a.userId()));
        invalidate(a.userId());
    }

    @Transactional
    public void bindEmail(ActorContext a, String email, String password) {
        String e = email(email);
        rate.require("email/" + a.userId(), 5);
        String hash =
                db.queryForObject(
                        "SELECT password_hash FROM app_user WHERE id=? FOR UPDATE",
                        String.class,
                        bytes(a.userId()));
        if (password == null || !encoder.matches(password, hash))
            throw new ApiException(403, "PASSWORD_MISMATCH", "需要当前密码");
        try {
            db.update(
                    "UPDATE app_user SET email=?,email_verified=FALSE WHERE id=?",
                    e,
                    bytes(a.userId()));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(409, "EMAIL_EXISTS", "邮箱已被使用");
        }
        db.update(
                "UPDATE account_token SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND used_at IS"
                        + " NULL",
                bytes(a.userId()));
        issue(a.userId(), e, "verify", 1440, null);
    }

    private void invalidate(UUID user) {
        db.update(
                "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME COLLATE utf8mb4_0900_as_cs=(SELECT"
                        + " login FROM app_user WHERE id=?)",
                bytes(user));
    }
}
