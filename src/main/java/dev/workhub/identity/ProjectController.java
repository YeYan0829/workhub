package dev.workhub.identity;

import dev.workhub.common.Inputs;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
public class ProjectController {
    private final ProjectService service;
    private final CurrentActor actors;

    public ProjectController(ProjectService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record CreateProject(String name) {}

    public record Membership(ProjectRole role, boolean active, Long expectedBusinessRevision) {}

    @PostMapping("/workspaces/{w}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Project create(
            Principal user,
            @PathVariable UUID w,
            @RequestBody CreateProject request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.create(actors.from(user), w, request.name(), key);
    }

    @GetMapping("/workspaces/{w}/projects")
    public List<Project> list(Principal user, @PathVariable UUID w) {
        return service.list(actors.from(user), w);
    }

    @GetMapping("/projects/{p}")
    public Project get(Principal user, @PathVariable UUID p) {
        return service.get(actors.from(user), p);
    }

    @PutMapping("/projects/{p}/members/{u}")
    public Project member(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID u,
            @RequestBody Membership request) {
        return service.member(
                actors.from(user),
                p,
                u,
                request.role(),
                request.active(),
                Inputs.revision(request.expectedBusinessRevision()));
    }
}
