package dev.workhub.identity;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Inputs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class IdentityBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String login, password, workspace;

    public IdentityBootstrap(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            @Value("${workhub.bootstrap.login:}") String login,
            @Value("${workhub.bootstrap.password:}") String password,
            @Value("${workhub.bootstrap.workspace:Work Hub}") String workspace) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.login = login;
        this.password = password;
        this.workspace = workspace;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (login.isBlank()) return;
        Inputs.text(login, "bootstrap login", 100);
        Inputs.text(password, "bootstrap password", 200);
        if (password.length() < 12)
            throw new IllegalArgumentException("Bootstrap password must be at least 12 characters");
        // Explicit first-deployment provisioning; never overwrite existing users or passwords.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) > 0) return;
        UUID user = UUID.randomUUID(), space = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,?)",
                bytes(user),
                login,
                login,
                encoder.encode(password));
        jdbc.update("INSERT INTO workspace(id,name) VALUES (?,?)", bytes(space), workspace);
        jdbc.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'ADMIN')",
                bytes(space),
                bytes(user));
    }
}
