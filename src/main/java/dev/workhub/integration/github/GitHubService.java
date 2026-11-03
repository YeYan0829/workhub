package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.JsonNode;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.TaskRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.security.*;
import java.util.*;

@Service
public class GitHubService {
    private final GitHubStore store;
    private final ProjectAccess access;
    private final OperationReceipts receipts;
    private final TaskRepository tasks;
    private final String secret;

    public GitHubService(
            GitHubStore store,
            ProjectAccess access,
            OperationReceipts receipts,
            TaskRepository tasks,
            @Value("${workhub.github.webhook-secret:}") String secret) {
        this.store = store;
        this.access = access;
        this.receipts = receipts;
        this.tasks = tasks;
        this.secret = secret;
    }

    public record Connect(long installationId, long repositoryId, String repositoryName) {}

    public record IntentRequest(
            long expectedTaskRevision, long expectedSyncRevision, JsonNode desired) {}

    public record Accepted(UUID id, String status) {}

    public record SyncView(
            long syncRevision,
            String status,
            String availability,
            java.time.Instant confirmedAt,
            String error,
            GitHubStore.Intent intent) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID connect(ActorContext actor, UUID project, Connect request, String key) {
        access.lock(actor, project);
        access.maintainer(actor, project);
        if (request.installationId() <= 0
                || request.repositoryId() <= 0
                || request.repositoryName() == null
                || !request.repositoryName().matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")
                || request.repositoryName().contains(".."))
            throw ApiException.invalid("Invalid installation/repository mapping");
        String hash = Inputs.hash(store.json().write(List.of("github-connect", project, request)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return previous;
        if (store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM github_repository_grant r JOIN"
                                        + " github_installation_grant g ON"
                                        + " g.installation_id=r.installation_id JOIN project p ON"
                                        + " p.workspace_id=g.workspace_id WHERE p.id=? AND"
                                        + " g.installation_id=? AND r.repository_id=? AND"
                                        + " r.repository_name=?",
                                Integer.class,
                                bytes(project),
                                request.installationId(),
                                request.repositoryId(),
                                request.repositoryName())
                != 1)
            throw new ApiException(403, "REPOSITORY_NOT_AUTHORIZED", "请部署者先预授权此 Workspace 与仓库");
        UUID id = UUID.randomUUID();
        try {
            store.jdbc()
                    .update(
                            "INSERT INTO"
                                + " github_connection(id,project_id,installation_id,repository_id,repository_name,import_actor)"
                                + " VALUES (?,?,?,?,?,?)",
                            bytes(id),
                            bytes(project),
                            request.installationId(),
                            request.repositoryId(),
                            request.repositoryName(),
                            bytes(actor.userId()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(
                    409, "REPOSITORY_ALREADY_CONNECTED", "Repository already mapped");
        }
        receipts.save(actor.userId(), key, project, hash, id);
        scan(store.connection(id));
        management(actor, project, "GITHUB_CONNECTED", id);
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Accepted webhook(String delivery, String event, String signature, byte[] raw) {
        if (raw.length > 2_000_000)
            throw new ApiException(413, "WEBHOOK_TOO_LARGE", "Webhook too large");
        if (!WebhookSignature.valid(raw, signature, secret))
            throw new ApiException(401, "INVALID_SIGNATURE", "Invalid webhook signature");
        Inputs.text(delivery, "delivery", 128);
        Inputs.text(event, "event", 80);
        JsonNode payload;
        try {
            payload = store.mapper().readTree(raw);
        } catch (Exception e) {
            throw ApiException.invalid("Invalid webhook JSON");
        }
        if (payload == null || !payload.isObject())
            throw ApiException.invalid("Invalid webhook JSON object");
        // App-level ping has no repository/installation mapping. It only probes the
        // signed endpoint: acknowledge without persisting or scheduling domain work.
        if (event.equals("ping")) return new Accepted(null, "PING");
        long installation = payload.path("installation").path("id").asLong(),
                repository = payload.path("repository").path("id").asLong();
        var ids =
                store.jdbc()
                        .query(
                                "SELECT id FROM github_connection WHERE installation_id=? AND"
                                        + " repository_id=? AND enabled",
                                (r, n) -> uuid(r.getBytes(1)),
                                installation,
                                repository);
        if (ids.isEmpty()) throw ApiException.forbidden();
        var c = store.connection(ids.get(0));
        store.lockProject(c.project());
        if (!store.connection(c.id()).enabled()) throw ApiException.forbidden();
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var existing =
                store.jdbc()
                        .queryForList(
                                "SELECT id,payload_hash,event_type FROM webhook_inbox WHERE"
                                        + " connection_id=? AND delivery_id=?",
                                bytes(c.id()),
                                delivery);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            UUID id = uuid((byte[]) row.get("id"));
            if (!hash.equals(row.get("payload_hash")) || !event.equals(row.get("event_type"))) {
                store.jdbc()
                        .update(
                                "UPDATE webhook_inbox SET hash_conflict=TRUE WHERE id=?",
                                bytes(id));
                return new Accepted(
                        id, "HASH_CONFLICT"); // Commit the audit before Controller returns 409.
            }
            return new Accepted(id, "DUPLICATE");
        }
        String action = payload.path("action").asText("");
        if (action.length() > 80) throw ApiException.invalid("Invalid action");
        boolean supported =
                Set.of("issues", "issue_comment").contains(event)
                        && !payload.path("issue").has("pull_request")
                        && payload.path("issue").path("id").asLong() > 0
                        && payload.path("issue").path("number").asLong() > 0;
        UUID id = UUID.randomUUID();
        store.jdbc()
                .update(
                        "INSERT INTO"
                            + " webhook_inbox(id,connection_id,delivery_id,payload_hash,raw_body,event_type,action,status)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                        bytes(id),
                        bytes(c.id()),
                        delivery,
                        hash,
                        raw,
                        event,
                        action,
                        supported ? "ACCEPTED" : "IGNORED");
        if (supported)
            store.job(
                    c.project(),
                    c.id(),
                    "github/route/" + id,
                    Map.of("stage", "ROUTE", "inbox", id),
                    store.now());
        return new Accepted(id, supported ? "ACCEPTED" : "IGNORED");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GitHubStore.Intent intent(
            ActorContext actor, UUID project, UUID task, IntentRequest request, String key) {
        access.lock(actor, project);
        var current = tasks.find(project, task).orElseThrow(ApiException::missing);
        access.editTask(actor, current);
        if (!access.isMaintainer(actor, project)
                && request.desired() != null
                && (request.desired().has("title")
                        || "not_planned".equals(request.desired().path("state_reason").asText())))
            throw new ApiException(403, "TASK_MANAGEMENT_FORBIDDEN", "GitHub 任务标题和正式取消由项目维护者管理");
        if (!access.isMaintainer(actor, project)
                && current.triageRequired()
                && request.desired() != null
                && request.desired().has("state"))
            throw new ApiException(403, "TASK_APPROVAL_REQUIRED", "待分诊任务不能通过 GitHub 请求提前推进状态");
        UUID resource = resource(project, task);
        var issue = store.issue(resource, true);
        String hash =
                Inputs.hash(store.json().write(List.of("github-intent", project, task, request)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return store.intent(previous);
        if (current.revision() != request.expectedTaskRevision())
            throw ApiException.conflict("TASK_REVISION_CONFLICT", current.revision());
        if (issue.syncRevision() != request.expectedSyncRevision())
            throw ApiException.conflict("SYNC_REVISION_CONFLICT", issue.syncRevision());
        if (!store.connection(issue.connection()).enabled()
                || !issue.availability().equals("AVAILABLE")
                || issue.snapshot() == null)
            throw new ApiException(
                    409, "EXTERNAL_UNAVAILABLE", "Refresh the connection before sending");
        var active = store.latest(resource);
        if (active != null && active.sent() != null && active.state().equals("ERROR"))
            throw new ApiException(409, "SYNC_OBSERVATION_REQUIRED", "已发送请求的结果仍不确定，只能通过对账观察确认");
        if (active != null && active.active())
            throw new ApiException(409, "SYNC_IN_PROGRESS", "An intent is active");
        try {
            IssueSnapshot.validatePatch(request.desired());
        } catch (RuntimeException e) {
            throw ApiException.invalid("Invalid GitHub desired patch");
        }
        UUID id = UUID.randomUUID();
        store.jdbc()
                .update(
                        "INSERT INTO"
                            + " sync_intent(id,project_id,resource_id,actor_id,task_revision,sync_revision,base_snapshot,desired_patch)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                        bytes(id),
                        bytes(project),
                        bytes(resource),
                        bytes(actor.userId()),
                        current.revision(),
                        issue.syncRevision(),
                        issue.snapshot(),
                        store.json().write(request.desired()));
        store.jdbc()
                .update(
                        "UPDATE github_issue SET sync_revision=sync_revision+1 WHERE resource_id=?",
                        bytes(resource));
        store.job(
                project,
                resource,
                "github/dispatch/" + id,
                Map.of("stage", "DISPATCH", "intent", id),
                store.now());
        receipts.save(actor.userId(), key, project, hash, id);
        return store.intent(id);
    }

    @Transactional(readOnly = true)
    public SyncView view(ActorContext actor, UUID project, UUID task) {
        access.read(actor, project);
        var i = store.issue(resource(project, task), false);
        var intent = store.latest(i.resource());
        boolean error =
                i.error() != null
                        || intent != null && Set.of("ERROR", "CONFLICT").contains(intent.state());
        String status =
                error
                        ? "ERROR"
                        : i.requested() > i.applied() || intent != null && intent.active()
                                ? "PENDING"
                                : "SYNCED";
        return new SyncView(
                i.syncRevision(), status, i.availability(), i.confirmedAt(), i.error(), intent);
    }

    private UUID resource(UUID project, UUID task) {
        return store
                .jdbc()
                .query(
                        "SELECT resource_id FROM github_issue WHERE project_id=? AND task_id=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(project),
                        bytes(task))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID reconcile(ActorContext actor, UUID project, UUID connection) {
        access.lock(actor, project);
        access.maintainer(actor, project);
        var c = store.connection(connection);
        if (!c.project().equals(project)) throw ApiException.missing();
        if (!c.enabled()) throw new ApiException(409, "CONNECTION_DISABLED", "请先恢复连接");
        store.jdbc()
                .update(
                        "UPDATE github_connection SET import_actor=? WHERE id=?",
                        bytes(actor.userId()),
                        bytes(connection));
        management(actor, project, "GITHUB_RECONCILE_REQUESTED", connection);
        return scan(store.connection(connection));
    }

    private void management(ActorContext actor, UUID project, String kind, UUID connection) {
        store.jdbc()
                .update(
                        "INSERT INTO"
                            + " management_activity(workspace_id,project_id,actor_id,kind,detail)"
                            + " SELECT workspace_id,id,?,?,? FROM project WHERE id=?",
                        bytes(actor.userId()),
                        kind,
                        store.json().write(Map.of("connection", connection)),
                        bytes(project));
    }

    UUID scan(GitHubStore.Connection c) {
        UUID round = UUID.randomUUID();
        store.jdbc()
                .update(
                        "INSERT INTO github_operation(id,connection_id,actor_id) VALUES (?,?,?)",
                        bytes(round),
                        bytes(c.id()),
                        bytes(c.actor()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("stage", "SCAN");
        payload.put("page", 1);
        payload.put("round", round);
        payload.put("until", store.now().toString());
        payload.put(
                "since", c.watermark() == null ? "" : c.watermark().minusSeconds(120).toString());
        store.job(c.project(), c.id(), "github/scan/" + round + "/1", payload, store.now());
        return round;
    }
}
