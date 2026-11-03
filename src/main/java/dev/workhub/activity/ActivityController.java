package dev.workhub.activity;

import dev.workhub.identity.CurrentActor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}")
public class ActivityController {
    private final ActivityService service;
    private final CurrentActor actors;

    public ActivityController(ActivityService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/activities")
    public List<ActivityRepository.Activity> activities(
            Principal user,
            @PathVariable UUID p,
            @RequestParam(defaultValue = "9223372036854775807") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(actors.from(user), p, before, limit);
    }

    @GetMapping("/notifications")
    public List<ActivityRepository.Notification> notifications(
            Principal user,
            @PathVariable UUID p,
            @RequestParam(defaultValue = "9223372036854775807") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return service.notifications(actors.from(user), p, before, limit);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(Principal user, @PathVariable UUID p, @PathVariable long id) {
        service.markRead(actors.from(user), p, id);
    }
}
