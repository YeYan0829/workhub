package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.onboarding.Workspaces;

import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}/github")
public class GitHubManagement {
    private final GitHubStore store;
    private final ProjectAccess access;
    private final CurrentActor actors;
    private final Workspaces spaces;
    private final Environment environment;

    public GitHubManagement(
            GitHubStore store,
            ProjectAccess access,
            CurrentActor actors,
            Workspaces spaces,
            Environment environment) {
        this.store = store;
        this.access = access;
        this.actors = actors;
        this.spaces = spaces;
        this.environment = environment;
    }

    @GetMapping("/setup")
    public Object setup(Principal u, @PathVariable UUID p) {
        var actor = actors.from(u);
        var project = access.read(actor, p);
        access.maintainer(actor, p);
        boolean configured =
                !environment.getProperty("workhub.github.app-id", "").isBlank()
                        && !environment
                                .getProperty("workhub.github.private-key-file", "")
                                .isBlank();
        String installUrl = environment.getProperty("workhub.github.install-url", "");
        // Only a public GitHub installation URL may be offered. Never expose credentials.
        if (!installUrl.matches("https://github\\.com/apps/[A-Za-z0-9-]+/installations/new"))
            installUrl = "";
        long authorized =
                store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM github_repository_grant r JOIN"
                                    + " github_installation_grant g ON"
                                    + " g.installation_id=r.installation_id WHERE g.workspace_id=?",
                                Long.class,
                                bytes(project.workspaceId()));
        return Map.of(
                "credentialsConfigured", configured,
                "readEnabled",
                        environment.getProperty("workhub.github.enabled", Boolean.class, false),
                "writeEnabled",
                        environment.getProperty(
                                "workhub.github.dispatch-enabled", Boolean.class, false),
                "installUrl", installUrl,
                "authorizedRepositories", authorized);
    }

    @GetMapping("/repositories")
    public Object repositories(Principal u, @PathVariable UUID p) {
        var a = actors.from(u);
        var project = access.read(a, p);
        access.maintainer(a, p);
        return store.jdbc()
                .queryForList(
                        "SELECT g.installation_id installationId,r.repository_id"
                            + " repositoryId,r.repository_name repositoryName,EXISTS(SELECT 1 FROM"
                            + " github_connection c WHERE c.repository_id=r.repository_id)"
                            + " connected FROM github_repository_grant r JOIN"
                            + " github_installation_grant g ON g.installation_id=r.installation_id"
                            + " WHERE g.workspace_id=?",
                        bytes(project.workspaceId()));
    }

    @GetMapping("/connections")
    public Object connections(Principal u, @PathVariable UUID p) {
        access.read(actors.from(u), p);
        return store.jdbc()
                .queryForList(
                        "SELECT BIN_TO_UUID(c.id) id,c.repository_name"
                            + " repositoryName,c.enabled,c.scan_watermark scanWatermark,(SELECT"
                            + " COUNT(*) FROM github_issue i WHERE i.connection_id=c.id AND"
                            + " i.task_id IS NOT NULL) imported,(SELECT COUNT(*) FROM github_issue"
                            + " i WHERE i.connection_id=c.id AND i.error_code IS NOT NULL) errors,"
                            + " (SELECT MAX(o.completed_at) FROM github_operation o WHERE"
                            + " o.connection_id=c.id AND o.state='SUCCEEDED') lastSuccessfulScanAt"
                            + " FROM github_connection c WHERE c.project_id=?",
                        bytes(p));
    }

    public record Enabled(boolean enabled) {}

    @PatchMapping("/connections/{id}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void enabled(
            Principal u, @PathVariable UUID p, @PathVariable UUID id, @RequestBody Enabled r) {
        var a = actors.from(u);
        var project = access.lockManagement(a, p);
        access.maintainer(a, p);
        if (!store.connection(id).project().equals(p)) throw ApiException.missing();
        store.jdbc()
                .update(
                        "UPDATE github_connection SET"
                            + " enabled=?,import_actor=?,next_scan_at=UTC_TIMESTAMP(6) WHERE id=?",
                        r.enabled(),
                        bytes(a.userId()),
                        bytes(id));
        spaces.audit(
                a,
                project.workspaceId(),
                p,
                "GITHUB_CONNECTION_CHANGED",
                Map.of("connection", id, "enabled", r.enabled()));
    }

    @GetMapping("/operations")
    public Object operations(Principal u, @PathVariable UUID p) {
        access.read(actors.from(u), p);
        return store.jdbc()
                .queryForList(
                        """
SELECT BIN_TO_UUID(o.id) id,c.repository_name repositoryName,o.pages,o.error_code error,o.created_at createdAt,
CASE WHEN o.state='DISCOVERED' THEN CASE WHEN EXISTS(SELECT 1 FROM github_operation_resource x JOIN external_resource r ON r.id=x.resource_id JOIN github_issue i ON i.resource_id=r.id WHERE x.operation_id=o.id AND r.applied_seq<x.requested_seq AND i.error_code IS NULL) THEN 'RUNNING' WHEN EXISTS(SELECT 1 FROM github_operation_resource x JOIN github_issue i ON i.resource_id=x.resource_id JOIN external_resource er ON er.id=x.resource_id WHERE x.operation_id=o.id AND er.applied_seq<x.requested_seq AND i.error_code IS NOT NULL) THEN 'PARTIAL_FAILED' ELSE 'SUCCEEDED' END ELSE o.state END state,
(SELECT COUNT(*) FROM github_operation_resource x WHERE x.operation_id=o.id) total,
(SELECT COUNT(*) FROM github_operation_resource x JOIN external_resource r ON r.id=x.resource_id WHERE x.operation_id=o.id AND r.applied_seq>=x.requested_seq) succeeded,
(SELECT COUNT(*) FROM github_operation_resource x JOIN github_issue i ON i.resource_id=x.resource_id JOIN external_resource er ON er.id=x.resource_id WHERE x.operation_id=o.id AND er.applied_seq<x.requested_seq AND i.error_code IS NOT NULL) failed,
o.completed_at scanCompletedAt
FROM github_operation o JOIN github_connection c ON c.id=o.connection_id WHERE c.project_id=? ORDER BY o.created_at DESC LIMIT 50
""",
                        bytes(p));
    }
}
