package dev.workhub.onboarding;

import dev.workhub.identity.*;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
public class WorkspaceController {
    private final Workspaces service;
    private final CurrentActor actors;

    public WorkspaceController(Workspaces service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record Name(String name, long revision) {}

    public record Email(String email) {}

    public record Membership(String role, boolean active) {}

    public record Handover(UUID userId) {}

    public record Accept(String token) {}

    @PostMapping("/workspaces")
    public UUID create(Principal p, @RequestBody Name r) {
        return service.create(actors.from(p), r.name());
    }

    @GetMapping("/workspaces/{w}")
    public Map<String, Object> get(Principal p, @PathVariable UUID w) {
        return service.get(actors.from(p), w);
    }

    @PatchMapping("/workspaces/{w}")
    public void rename(Principal p, @PathVariable UUID w, @RequestBody Name r) {
        service.rename(actors.from(p), w, r.name(), r.revision());
    }

    @GetMapping("/workspaces/{w}/members")
    public Object members(Principal p, @PathVariable UUID w) {
        return service.members(actors.from(p), w);
    }

    @PutMapping("/workspaces/{w}/members/{u}")
    public void member(
            Principal p, @PathVariable UUID w, @PathVariable UUID u, @RequestBody Membership r) {
        service.membership(actors.from(p), w, u, r.role(), r.active());
    }

    @GetMapping("/workspaces/{w}/invitations")
    public Object invitations(Principal p, @PathVariable UUID w) {
        return service.invitations(actors.from(p), w);
    }

    @PostMapping("/workspaces/{w}/invitations")
    public UUID invite(Principal p, @PathVariable UUID w, @RequestBody Email r) {
        return service.invite(actors.from(p), w, r.email());
    }

    @DeleteMapping("/workspaces/{w}/invitations/{i}")
    public void revoke(Principal p, @PathVariable UUID w, @PathVariable UUID i) {
        service.revoke(actors.from(p), w, i);
    }

    @GetMapping("/me/invitations")
    public Object pendingInvitations(Principal p) {
        return service.pendingInvitations(actors.from(p));
    }

    @PostMapping("/me/invitations/{id}/accept")
    public UUID acceptInvitation(Principal p, @PathVariable UUID id) {
        return service.acceptInvitation(actors.from(p), id);
    }

    @PostMapping("/invitations/preview")
    public Workspaces.InvitationView preview(@RequestBody Accept r) {
        return service.preview(r.token());
    }

    @PostMapping("/invitations/accept")
    public UUID accept(Principal p, @RequestBody Accept r) {
        return service.accept(actors.from(p), r.token());
    }

    @GetMapping("/workspaces/{w}/management/projects")
    public Object projects(Principal p, @PathVariable UUID w) {
        return service.projects(actors.from(p), w);
    }

    @PostMapping("/workspaces/{w}/management/projects/{project}/handover")
    public void handover(
            Principal p,
            @PathVariable UUID w,
            @PathVariable UUID project,
            @RequestBody Handover r) {
        service.handover(actors.from(p), w, project, r.userId());
    }

    @GetMapping("/workspaces/{w}/management/activities")
    public Object audit(Principal p, @PathVariable UUID w) {
        return service.audit(actors.from(p), w);
    }
}
