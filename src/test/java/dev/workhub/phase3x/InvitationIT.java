package dev.workhub.phase3x;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.onboarding.*;

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
class InvitationIT {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry p) {
        CollaborationIT.db(p);
    }

    @Autowired JdbcTemplate db;
    @Autowired Accounts accounts;
    @Autowired Workspaces spaces;
    @Autowired MailService mail;
    @Autowired MockMvc http;
    String ownerLogin, recipientLogin, email, token;
    ActorContext owner;
    UUID workspace, invitation;

    @BeforeEach
    void fixture() {
        ownerLogin = "inviteowner" + UUID.randomUUID().toString().replace("-", "");
        accounts.register(ownerLogin, ownerLogin + "@example.test", "Owner", "test-password-12345");
        accounts.consume(mailToken(ownerLogin + "@example.test", "verify"), "verify", null);
        owner = actor(ownerLogin);
        workspace = spaces.create(owner, "Invitation team");
        recipientLogin = "inviteguest" + UUID.randomUUID().toString().replace("-", "");
        email = recipientLogin + "@example.test";
        invitation = spaces.invite(owner, workspace, email);
        token = mailToken(email, "invite");
    }

    ActorContext actor(String login) {
        return new ActorContext(
                db.queryForObject(
                        "SELECT id FROM app_user WHERE login=?", (r, n) -> read(r, "id"), login));
    }

    String mailBody(String email) {
        return mail.decrypt(
                db.queryForObject(
                        "SELECT encrypted_body FROM mail_delivery WHERE recipient=? ORDER BY"
                                + " created_at DESC LIMIT 1",
                        String.class,
                        email));
    }

    String mailToken(String email, String purpose) {
        return mailBody(email).split("/#" + purpose + "/")[1].split("[?\\s]")[0];
    }

    @Test
    void anonymousPreviewRequiresInvitationTokenAndDoesNotGrantAccess() throws Exception {
        http.perform(
                        post("/invitations/preview")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.workspaceName").value("Invitation team"))
                .andExpect(jsonPath("$.accountState").value("REGISTER"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        http.perform(
                        post("/invitations/preview")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"token\":\"unknown\"}"))
                .andExpect(status().isGone());
        http.perform(
                        post("/invitations/accept")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
        http.perform(
                        post("/invitations/preview")
                                .contentType("application/json")
                                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(spaces.members(owner, workspace)).hasSize(1);
    }

    @Test
    void wrongAccountHasSpecificErrorAndInvitationRemainsUsable() throws Exception {
        http.perform(
                        post("/invitations/accept")
                                .with(user(ownerLogin))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_ACCOUNT_MISMATCH"));
        assertThat(spaces.preview(token).status()).isEqualTo("PENDING");
        assertThat(spaces.members(owner, workspace)).hasSize(1);
    }

    @Test
    void registrationVerificationAndLoginPreserveInvitationWithoutAutoAccepting() throws Exception {
        http.perform(
                        post("/account/register")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        new Json(new com.fasterxml.jackson.databind.ObjectMapper())
                                                .write(
                                                        Map.of(
                                                                "login",
                                                                recipientLogin,
                                                                "email",
                                                                email,
                                                                "displayName",
                                                                "Guest",
                                                                "password",
                                                                "test-password-12345",
                                                                "invitationToken",
                                                                token))))
                .andExpect(status().isOk());
        assertThat(spaces.preview(token).accountState()).isEqualTo("VERIFY");
        assertThat(mailBody(email)).contains("?invitation=" + token);
        ActorContext recipient = actor(recipientLogin);
        assertThatThrownBy(() -> spaces.accept(recipient, token))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code).isEqualTo("INVITATION_EMAIL_UNVERIFIED"));
        String firstVerification = mailToken(email, "verify");
        accounts.request(email, "verify", token);
        assertThat(mailBody(email)).contains("?invitation=" + token);
        assertThatThrownBy(() -> accounts.consume(firstVerification, "verify", null))
                .isInstanceOf(ApiException.class);
        accounts.consume(mailToken(email, "verify"), "verify", null);
        assertThat(spaces.preview(token).accountState()).isEqualTo("LOGIN");
        assertThat(spaces.members(owner, workspace)).hasSize(1);
        assertThat(spaces.accept(recipient, token)).isEqualTo(workspace);
        assertThat(spaces.accept(recipient, token)).isEqualTo(workspace);
        assertThat(spaces.preview(token).status()).isEqualTo("ACCEPTED");
        assertThat(spaces.members(owner, workspace)).hasSize(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM project_member WHERE user_id=?",
                                Integer.class,
                                bytes(recipient.userId())))
                .isZero();
    }

    @Test
    void pendingInvitationsUseCurrentVerifiedEmailAndAcceptanceAppearsInSession() throws Exception {
        accounts.register(recipientLogin, email, "Guest", "test-password-12345");
        ActorContext recipient = actor(recipientLogin);
        assertThat(spaces.pendingInvitations(recipient)).isEmpty();
        accounts.consume(mailToken(email, "verify"), "verify", null);
        http.perform(get("/me/invitations")).andExpect(status().isForbidden());
        http.perform(get("/me/invitations").with(user(ownerLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        http.perform(get("/me/invitations").with(user(recipientLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invitation.toString()))
                .andExpect(jsonPath("$[0].workspaceName").value("Invitation team"))
                .andExpect(jsonPath("$[0].email").value(email))
                .andExpect(jsonPath("$[0].token_hash").doesNotExist());
        http.perform(
                        post("/me/invitations/" + invitation + "/accept")
                                .with(user(ownerLogin))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/me/invitations/" + invitation + "/accept").with(user(recipientLogin)))
                .andExpect(status().isForbidden());
        http.perform(
                        post("/me/invitations/" + invitation + "/accept")
                                .with(user(recipientLogin))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string("\"" + workspace + "\""));
        http.perform(get("/session/me").with(user(recipientLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].id").value(workspace.toString()));
        assertThat(spaces.pendingInvitations(recipient)).isEmpty();
        spaces.membership(owner, workspace, recipient.userId(), "MEMBER", false);
        assertThat(spaces.pendingInvitations(recipient)).isEmpty();
        assertThatThrownBy(() -> spaces.acceptInvitation(recipient, invitation))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code).isEqualTo("INVITATION_MEMBERSHIP_ENDED"));
        assertThatThrownBy(() -> spaces.accept(recipient, token))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code).isEqualTo("INVITATION_MEMBERSHIP_ENDED"));
        http.perform(get("/session/me").with(user(recipientLogin)))
                .andExpect(jsonPath("$.workspaces").isEmpty());
        UUID next = spaces.invite(owner, workspace, email);
        spaces.revoke(owner, workspace, next);
        assertThat(spaces.pendingInvitations(recipient)).isEmpty();
        http.perform(
                        post("/me/invitations/" + next + "/accept")
                                .with(user(recipientLogin))
                                .with(csrf()))
                .andExpect(status().isGone());
        UUID expired = spaces.invite(owner, workspace, email);
        db.update(
                "UPDATE workspace_invitation SET expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(expired));
        assertThat(spaces.pendingInvitations(recipient)).isEmpty();
        http.perform(
                        post("/me/invitations/" + expired + "/accept")
                                .with(user(recipientLogin))
                                .with(csrf()))
                .andExpect(status().isGone());
    }

    @Test
    void invitationContinuationCannotBeAttachedToAnotherEmail() {
        assertThatThrownBy(
                        () ->
                                accounts.register(
                                        recipientLogin,
                                        "other_" + email,
                                        "Guest",
                                        "test-password-12345",
                                        token))
                .isInstanceOf(ApiException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM app_user WHERE login=?",
                                Integer.class,
                                recipientLogin))
                .isZero();
        assertThatThrownBy(() -> accounts.request(ownerLogin + "@example.test", "verify", token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void revokedExpiredAndUnknownInvitationsCannotContinueRegistration() {
        spaces.revoke(owner, workspace, invitation);
        assertThat(spaces.preview(token).status()).isEqualTo("REVOKED");
        assertThatThrownBy(
                        () ->
                                accounts.register(
                                        recipientLogin,
                                        email,
                                        "Guest",
                                        "test-password-12345",
                                        token))
                .isInstanceOf(ApiException.class);
        invitation = spaces.invite(owner, workspace, email);
        token = mailToken(email, "invite");
        db.update(
                "UPDATE workspace_invitation SET expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6))"
                        + " WHERE id=?",
                bytes(invitation));
        assertThat(spaces.preview(token).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(
                        () ->
                                accounts.register(
                                        recipientLogin,
                                        email,
                                        "Guest",
                                        "test-password-12345",
                                        token))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> spaces.preview(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> spaces.preview("missing")).isInstanceOf(ApiException.class);
    }
}
