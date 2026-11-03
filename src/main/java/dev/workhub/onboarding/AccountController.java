package dev.workhub.onboarding;

import dev.workhub.identity.*;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
public class AccountController {
    private final Accounts service;
    private final CurrentActor actors;

    public AccountController(Accounts service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record Registration(
            String login,
            String email,
            String displayName,
            String password,
            String invitationToken) {}

    public record BindEmail(String email, String password) {}

    @PostMapping("/account/email")
    public void email(Principal p, @RequestBody BindEmail r) {
        service.bindEmail(actors.from(p), r.email(), r.password());
    }

    public record Email(String email, String invitationToken) {}

    public record Token(String token, String password) {}

    public record Profile(String displayName) {}

    public record Password(String oldPassword, String password) {}

    @PostMapping("/account/register")
    public void register(@RequestBody Registration r) {
        service.register(r.login(), r.email(), r.displayName(), r.password(), r.invitationToken());
    }

    @PostMapping("/account/verification")
    public void verification(@RequestBody Email r) {
        service.request(r.email(), "verify", r.invitationToken());
    }

    @PostMapping("/account/forgot")
    public void forgot(@RequestBody Email r) {
        service.request(r.email(), "reset", r.invitationToken());
    }

    @PostMapping("/account/verify")
    public void verify(@RequestBody Token r) {
        service.consume(r.token(), "verify", null);
    }

    @PostMapping("/account/reset")
    public void reset(@RequestBody Token r) {
        service.consume(r.token(), "reset", r.password());
    }

    @GetMapping("/account/profile")
    public Map<String, Object> profile(Principal p) {
        return service.profile(actors.from(p));
    }

    @PatchMapping("/account/profile")
    public void profile(Principal p, @RequestBody Profile r) {
        service.profile(actors.from(p), r.displayName());
    }

    @PostMapping("/account/password")
    public void password(Principal p, @RequestBody Password r) {
        service.changePassword(actors.from(p), r.oldPassword(), r.password());
    }
}
