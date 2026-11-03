package dev.workhub.activity;

import dev.workhub.identity.CurrentActor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/me/notifications")
public class InboxController {
    private final ActivityService service;
    private final CurrentActor actors;

    public InboxController(ActivityService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public Object list(
            Principal user,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "9223372036854775807") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return service.inbox(actors.from(user), workspaceId, projectId, unreadOnly, before, limit);
    }

    @GetMapping("/unread-count")
    public Object count(Principal user) {
        return Map.of("count", service.unreadCount(actors.from(user)));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(Principal user, @PathVariable long id) {
        service.markRead(actors.from(user), id);
    }
}
