package dev.workhub.identity;

import dev.workhub.common.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final IdentityRepository identities;
    private final ProjectAccess access;
    private final OperationReceipts receipts;
    private final Json json;
    private final dev.workhub.onboarding.Workspaces workspaces;

    public ProjectService(
            ProjectRepository projects,
            IdentityRepository identities,
            ProjectAccess access,
            OperationReceipts receipts,
            Json json,
            dev.workhub.onboarding.Workspaces workspaces) {
        this.workspaces = workspaces;
        this.projects = projects;
        this.identities = identities;
        this.access = access;
        this.receipts = receipts;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Project create(ActorContext actor, UUID workspace, String name, String key) {
        Inputs.text(name, "name", 200);
        identities.lockWorkspace(workspace);
        identities.lockCreator(actor.userId());
        if (!identities.activeUser(actor.userId(), false)
                || !identities.activeWorkspaceMember(workspace, actor.userId(), true))
            throw ApiException.forbidden();
        String hash = Inputs.hash(json.write(List.of("project", workspace, name)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return access.read(actor, previous);
        UUID id = UUID.randomUUID();
        projects.insert(id, workspace, name);
        identities.saveMembership(id, actor.userId(), ProjectRole.MAINTAINER, true);
        receipts.save(actor.userId(), key, id, hash, id);
        return projects.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Project> list(ActorContext actor, UUID workspace) {
        if (!identities.activeUser(actor.userId(), false)
                || !identities.activeWorkspaceMember(workspace, actor.userId(), false))
            throw ApiException.forbidden();
        return projects.list(workspace, actor.userId());
    }

    @Transactional(readOnly = true)
    public Project get(ActorContext actor, UUID project) {
        return access.read(actor, project);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Project member(
            ActorContext actor,
            UUID project,
            UUID user,
            ProjectRole role,
            boolean active,
            long expected) {
        Project current = access.lock(actor, project);
        access.maintainer(actor, project);
        access.expected(current, expected);
        if (role == null
                || !identities.activeUser(user, false)
                || !identities.activeWorkspaceMember(current.workspaceId(), user, false))
            throw ApiException.invalid("Active workspace member and role required");
        if (!active || role != ProjectRole.MAINTAINER) workspaces.lastMaintainer(project, user);
        var old = identities.membership(project, user);
        if (old.isPresent() && old.get().role() == role && old.get().active() == active)
            return current;
        identities.saveMembership(project, user, role, active);
        workspaces.audit(
                actor,
                current.workspaceId(),
                project,
                "PROJECT_MEMBERSHIP_CHANGED",
                Map.of("user", user, "role", role, "active", active));
        projects.increment(project);
        return projects.find(project).orElseThrow();
    }
}
