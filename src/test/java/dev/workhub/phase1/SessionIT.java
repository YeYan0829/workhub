package dev.workhub.phase1;

import static dev.workhub.common.Ids.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.util.*;

class SessionIT extends Phase1Support {
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired ObjectMapper mapper;

    @Test
    void csrfLoginSessionRotationDatabasePersistenceAndLogout() throws Exception {
        String password = "phase1-test-password";
        jdbc.update(
                "UPDATE app_user SET password_hash=? WHERE id=?",
                encoder.encode(password),
                bytes(actor.userId()));
        var csrf =
                http.perform(get("/session/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        Cookie initial = csrf.getCookie("SESSION");
        assertThat(initial).isNotNull();
        assertThat(initial.isHttpOnly()).isTrue();
        var token = mapper.readTree(csrf.getContentAsString());
        http.perform(
                        post("/session/login")
                                .cookie(initial)
                                .param("username", login)
                                .param("password", password))
                .andExpect(status().isForbidden());
        var loginResponse =
                http.perform(
                                post("/session/login")
                                        .cookie(initial)
                                        .header(
                                                token.get("headerName").asText(),
                                                token.get("token").asText())
                                        .param("username", login)
                                        .param("password", password))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getResponse();
        Cookie authenticated = loginResponse.getCookie("SESSION");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(initial.getValue());
        String id =
                new String(
                        Base64.getDecoder().decode(authenticated.getValue()),
                        java.nio.charset.StandardCharsets.UTF_8);
        // This read hydrates security attributes from MySQL, independent of the request's in-memory
        // session.
        org.springframework.session.Session persisted = sessions.findById(id);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getAttributeNames()).contains("SPRING_SECURITY_CONTEXT");
        http.perform(get("/session/me").cookie(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(actor.userId().toString()));
        http.perform(get("/session/me").cookie(initial)).andExpect(status().isForbidden());
        var newCsrf =
                http.perform(get("/session/csrf").cookie(authenticated)).andReturn().getResponse();
        var newToken = mapper.readTree(newCsrf.getContentAsString());
        http.perform(
                        post("/session/logout")
                                .cookie(authenticated)
                                .header(
                                        newToken.get("headerName").asText(),
                                        newToken.get("token").asText()))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(id)).isNull();
        http.perform(get("/session/me").cookie(authenticated)).andExpect(status().isForbidden());
    }
}
