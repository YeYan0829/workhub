package dev.workhub.identity;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/session")
public class SessionController {
    private final CurrentActor actors;
    private final SessionService service;

    public SessionController(CurrentActor actors, SessionService service) {
        this.actors = actors;
        this.service = service;
    }

    public record CsrfResponse(String headerName, String token) {}

    public record Me(UUID userId, List<IdentityRepository.WorkspaceView> workspaces) {}

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public Me me(Principal principal) {
        var actor = actors.from(principal);
        return new Me(actor.userId(), service.workspaces(actor));
    }
}
