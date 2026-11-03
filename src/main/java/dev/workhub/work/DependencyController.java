package dev.workhub.work;

import dev.workhub.common.Inputs;
import dev.workhub.identity.CurrentActor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/projects/{p}/dependencies")
public class DependencyController {
    private final DependencyService service;
    private final CurrentActor actors;

    public DependencyController(DependencyService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record Request(UUID blockerId, UUID blockedId, Long expectedBusinessRevision) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DependencyService.Result add(
            Principal user,
            @PathVariable UUID p,
            @RequestBody Request request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.add(
                actors.from(user),
                p,
                Inputs.revision(request.expectedBusinessRevision()),
                new Dependency(request.blockerId(), request.blockedId()),
                key);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal user, @PathVariable UUID p, @RequestBody Request request) {
        service.remove(
                actors.from(user),
                p,
                Inputs.revision(request.expectedBusinessRevision()),
                new Dependency(request.blockerId(), request.blockedId()));
    }
}
