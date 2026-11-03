package dev.workhub.work;

import dev.workhub.identity.*;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

@RestController
public class CollaborationController {
    private final CollaborationService service;
    private final CurrentActor actors;

    public CollaborationController(CollaborationService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record Edit(
            String name, String description, boolean archived, long expectedBusinessRevision) {}

    @PatchMapping("/projects/{p}")
    public Project project(Principal u, @PathVariable UUID p, @RequestBody Edit r) {
        return service.project(
                actors.from(u),
                p,
                r.name(),
                r.description(),
                r.archived(),
                r.expectedBusinessRevision());
    }

    @GetMapping("/projects/{p}/members")
    public Object members(Principal u, @PathVariable UUID p) {
        return service.members(actors.from(u), p);
    }

    @GetMapping("/projects/{p}/summary")
    public Object summary(Principal u, @PathVariable UUID p) {
        return service.summary(actors.from(u), p);
    }

    @GetMapping({"/projects/{p}/task-search", "/me/tasks"})
    public Object query(
            Principal u,
            @PathVariable(required = false) UUID p,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Boolean blocked,
            @RequestParam(required = false) LocalDate due,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID projectId) {
        return service.query(
                actors.from(u),
                p,
                q,
                status,
                assignee,
                blocked,
                due,
                offset,
                limit,
                workspaceId,
                projectId);
    }

    @GetMapping("/me/projects")
    public Object projects(Principal u) {
        return service.accessibleProjects(actors.from(u));
    }

    @GetMapping("/projects/{p}/tasks/{t}/detail")
    public Object detail(Principal u, @PathVariable UUID p, @PathVariable UUID t) {
        return service.detail(actors.from(u), p, t);
    }
}
