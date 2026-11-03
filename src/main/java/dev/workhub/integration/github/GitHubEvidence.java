package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.JsonNode;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.TaskRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Read-only evidence never drives a task's workflow status. CI references pin attempt and SHA. */
@Service
public class GitHubEvidence {
    public record Link(
            UUID connection,
            long expectedTaskRevision,
            String kind,
            long number,
            Integer attempt,
            String sha) {}

    private final GitHubStore store;
    private final ProjectAccess access;
    private final TaskRepository tasks;
    private final ActivityService activity;
    private final OperationReceipts receipts;

    public GitHubEvidence(
            GitHubStore store,
            ProjectAccess access,
            TaskRepository tasks,
            ActivityService activity,
            OperationReceipts receipts) {
        this.store = store;
        this.access = access;
        this.tasks = tasks;
        this.activity = activity;
        this.receipts = receipts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID link(ActorContext actor, UUID project, UUID task, Link request, String key) {
        access.lock(actor, project);
        var t = tasks.find(project, task).orElseThrow(ApiException::missing);
        access.editTask(actor, t);
        var c = store.connection(request.connection());
        if (!c.project().equals(project)) throw ApiException.missing();
        String hash =
                Inputs.hash(store.json().write(List.of("github-evidence", project, task, request)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return previous;
        if (t.revision() != request.expectedTaskRevision())
            throw ApiException.conflict("TASK_REVISION_CONFLICT", t.revision());
        if (!Set.of("PR", "CI_RUN", "COMMIT").contains(request.kind())
                || request.number() < 0
                || !request.kind().equals("COMMIT") && request.number() == 0
                || Set.of("CI_RUN", "COMMIT").contains(request.kind())
                        && (request.sha() == null || !request.sha().matches("[0-9a-f]{40}"))
                || request.kind().equals("CI_RUN")
                        && (request.attempt() == null || request.attempt() <= 0))
            throw ApiException.invalid("Invalid evidence identity");
        String identity =
                "github/"
                        + c.repository()
                        + "/"
                        + request.kind()
                        + "/"
                        + request.number()
                        + "/"
                        + request.attempt()
                        + "/"
                        + request.sha();
        var existing =
                store.jdbc()
                        .query(
                                "SELECT id FROM external_resource WHERE project_id=? AND kind=? AND"
                                        + " provider_key=?",
                                (r, n) -> uuid(r.getBytes(1)),
                                bytes(project),
                                request.kind(),
                                identity);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
        if (existing.isEmpty()) {
            String url =
                    "https://github.com/"
                            + c.name()
                            + switch (request.kind()) {
                                case "PR" -> "/pull/" + request.number();
                                case "CI_RUN" ->
                                        "/actions/runs/"
                                                + request.number()
                                                + "/attempts/"
                                                + request.attempt();
                                default -> "/commit/" + request.sha();
                            };
            store.jdbc()
                    .update(
                            "INSERT INTO external_resource(id,project_id,kind,provider_key,url)"
                                    + " VALUES (?,?,?,?,?)",
                            bytes(id),
                            bytes(project),
                            request.kind(),
                            identity,
                            url);
            store.jdbc()
                    .update(
                            "INSERT INTO"
                                + " github_evidence(resource_id,project_id,connection_id,remote_number,run_attempt,head_sha)"
                                + " VALUES (?,?,?,?,?,?)",
                            bytes(id),
                            bytes(project),
                            bytes(c.id()),
                            request.number(),
                            request.attempt(),
                            request.sha());
        }
        int inserted =
                store.jdbc()
                        .update(
                                "INSERT IGNORE INTO task_resource(project_id,task_id,resource_id)"
                                        + " VALUES (?,?,?)",
                                bytes(project),
                                bytes(task),
                                bytes(id));
        if (inserted == 1) {
            store.jdbc()
                    .update(
                            "UPDATE task SET revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                                    + " id=?",
                            bytes(task));
            store.jdbc()
                    .update(
                            "UPDATE project SET business_revision=business_revision+1 WHERE id=?",
                            bytes(project));
            activity.record(
                    project,
                    task,
                    actor,
                    UUID.randomUUID(),
                    "RESOURCE_LINKED",
                    null,
                    Map.of("resourceId", id));
        }
        store.job(
                project,
                id,
                "github/evidence/" + id + "/" + UUID.randomUUID(),
                Map.of("stage", "EVIDENCE"),
                store.now());
        receipts.save(actor.userId(), key, project, hash, id);
        return id;
    }

    public void lock(UUID resource) {
        store.jdbc()
                .queryForList(
                        "SELECT id FROM external_resource WHERE id=? FOR UPDATE", bytes(resource));
    }

    public GitHubHandler.Call prepare(WorkJobRepository.Job job) {
        var row =
                store.jdbc()
                        .queryForMap(
                                "SELECT e.*,r.kind,r.lane_generation FROM github_evidence e JOIN"
                                        + " external_resource r ON r.id=e.resource_id WHERE"
                                        + " e.resource_id=?",
                                bytes(job.owner()));
        var c = store.connection(uuid((byte[]) row.get("connection_id")));
        String kind = (String) row.get("kind");
        long generation = ((Number) row.get("lane_generation")).longValue() + 1;
        store.jdbc()
                .update(
                        "UPDATE external_resource SET"
                            + " lane_generation=?,lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6))"
                            + " WHERE id=?",
                        generation,
                        bytes(job.owner()));
        String path =
                c.path()
                        + switch (kind) {
                            case "PR" -> "/pulls/" + row.get("remote_number");
                            case "CI_RUN" ->
                                    "/actions/runs/"
                                            + row.get("remote_number")
                                            + "/attempts/"
                                            + row.get("run_attempt");
                            default -> "/commits/" + row.get("head_sha");
                        };
        return new GitHubHandler.Call(c, job.owner(), generation, 0, "EVIDENCE", path, null, null);
    }

    public RemoteWorkHandler.Step complete(
            WorkJobRepository.Job job, GitHubHandler.Call call, GitHubClient.Reply reply) {
        var row =
                store.jdbc()
                        .queryForMap(
                                "SELECT e.*,r.kind,r.lane_generation,r.lease_until FROM"
                                        + " github_evidence e JOIN external_resource r ON"
                                        + " r.id=e.resource_id WHERE e.resource_id=?",
                                bytes(job.owner()));
        if (((Number) row.get("lane_generation")).longValue() != call.generation()
                || !GitHubStore.instant(row.get("lease_until")).isAfter(store.now()))
            throw new IllegalStateException("Evidence lane lost");
        if (reply.retryableRead() && job.attempt() < 5) {
            release(job.owner());
            return RemoteWorkHandler.Step.retryRead(
                    reply.retryAt() == null ? store.now().plusSeconds(5) : reply.retryAt());
        }
        String kind = (String) row.get("kind");
        JsonNode body = reply.body();
        boolean identity =
                reply.ok()
                        && switch (kind) {
                            case "CI_RUN" ->
                                    body.path("id").asLong()
                                                    == ((Number) row.get("remote_number"))
                                                            .longValue()
                                            && body.path("run_attempt").asInt()
                                                    == ((Number) row.get("run_attempt")).intValue()
                                            && body.path("head_sha")
                                                    .asText()
                                                    .equals(row.get("head_sha"));
                            case "COMMIT" -> body.path("sha").asText().equals(row.get("head_sha"));
                            default ->
                                    body.path("number").asLong()
                                                    == ((Number) row.get("remote_number"))
                                                            .longValue()
                                            && body.path("id").asLong() > 0;
                        };
        String state =
                !identity
                        ? "UNKNOWN"
                        : kind.equals("PR")
                                ? (body.path("merged").asBoolean()
                                        ? "MERGED"
                                        : body.path("state").asText("UNKNOWN"))
                                : kind.equals("CI_RUN")
                                        ? body.path("conclusion").asText("UNKNOWN")
                                        : "OBSERVED";
        if (!Set.of(
                        "UNKNOWN",
                        "MERGED",
                        "open",
                        "closed",
                        "OBSERVED",
                        "success",
                        "failure",
                        "neutral",
                        "cancelled",
                        "skipped",
                        "timed_out",
                        "action_required",
                        "stale")
                .contains(state)) state = "UNKNOWN";
        store.jdbc()
                .update(
                        "UPDATE external_resource SET"
                            + " confirmed_metadata=?,availability=?,confirmed_at=UTC_TIMESTAMP(6),observed_revision=observed_revision+1,lease_until=NULL"
                            + " WHERE id=?",
                        store.json()
                                .write(
                                        Map.of(
                                                "state",
                                                state,
                                                "raw",
                                                body,
                                                "identityMatched",
                                                identity)),
                        identity ? "AVAILABLE" : "UNAVAILABLE",
                        bytes(job.owner()));
        return RemoteWorkHandler.Step.done(
                "github/evidence/" + job.id() + "/" + (identity ? "OBSERVED" : "UNAVAILABLE"));
    }

    public record View(
            UUID id, String availability, java.time.Instant confirmedAt, JsonNode metadata) {}

    @Transactional(readOnly = true)
    public View get(ActorContext actor, UUID project, UUID resource) {
        access.read(actor, project);
        return store
                .jdbc()
                .query(
                        "SELECT r.* FROM external_resource r JOIN github_evidence e ON"
                                + " e.resource_id=r.id WHERE r.project_id=? AND r.id=?",
                        (r, n) ->
                                new View(
                                        resource,
                                        r.getString("availability"),
                                        Utc.read(r, "confirmed_at"),
                                        store.node(r.getString("confirmed_metadata"))),
                        bytes(project),
                        bytes(resource))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID refresh(ActorContext actor, UUID project, UUID resource) {
        access.lock(actor, project);
        get(actor, project, resource);
        return store.job(
                project,
                resource,
                "github/evidence/" + resource + "/" + UUID.randomUUID(),
                Map.of("stage", "EVIDENCE"),
                store.now());
    }

    private void release(UUID id) {
        store.jdbc().update("UPDATE external_resource SET lease_until=NULL WHERE id=?", bytes(id));
    }
}
