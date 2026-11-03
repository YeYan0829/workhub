package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.JsonNode;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class GitHubHandler implements RemoteWorkHandler {
    public record Call(
            GitHubStore.Connection connection,
            UUID resource,
            long generation,
            long captured,
            String operation,
            String path,
            JsonNode patch,
            UUID intent) {}

    private final GitHubStore store;
    private final GitHubClient client;
    private final TaskService tasks;
    private final TaskRepository taskRepo;
    private final ProjectAccess access;
    private final GitHubComments comments;
    private final GitHubEvidence evidence;
    private final org.springframework.context.ApplicationEventPublisher events;

    public GitHubHandler(
            GitHubStore store,
            GitHubClient client,
            TaskService tasks,
            TaskRepository taskRepo,
            ProjectAccess access,
            GitHubComments comments,
            GitHubEvidence evidence,
            org.springframework.context.ApplicationEventPublisher events) {
        this.store = store;
        this.client = client;
        this.tasks = tasks;
        this.taskRepo = taskRepo;
        this.access = access;
        this.comments = comments;
        this.evidence = evidence;
        this.events = events;
    }

    public String kind() {
        return "GITHUB_WORK";
    }

    public void lockBusiness(WorkJobRepository.Job job) {
        store.lockProject(job.project());
        String stage = store.node(job.payload()).path("stage").asText();
        if (stage.equals("EVIDENCE")) evidence.lock(job.owner());
        else if (!Set.of("ROUTE", "SCAN", "AUDIT").contains(stage)) store.issue(job.owner(), true);
    }

    public Step prepare(WorkJobRepository.Job job) {
        JsonNode p = store.node(job.payload());
        String stage = p.path("stage").asText();
        GitHubStore.Connection permissionConnection;
        if (Set.of("ROUTE", "SCAN", "AUDIT").contains(stage))
            permissionConnection = store.connection(job.owner());
        else if (stage.equals("EVIDENCE")) permissionConnection = null;
        else permissionConnection = store.connection(store.issue(job.owner(), false).connection());
        if (permissionConnection != null
                && !store.writable(permissionConnection)
                && !observingSent(job)) return stopped(job, "AUTHORIZATION_OR_ARCHIVE");
        if (stage.equals("EVIDENCE")) {
            var lease =
                    store.jdbc()
                            .queryForObject(
                                    "SELECT lease_until FROM external_resource WHERE id=?",
                                    (r, n) -> Utc.read(r, "lease_until"),
                                    bytes(job.owner()));
            if (lease != null && lease.isAfter(store.now()))
                return Step.retry(lease.plusMillis(10));
            Call call = evidence.prepare(job);
            if (!store.writable(call.connection())) return done(job, "DISCONNECTED");
            if (!admit(call.connection(), job)) {
                store.jdbc()
                        .update(
                                "UPDATE external_resource SET lease_until=NULL WHERE id=?",
                                bytes(job.owner()));
                return Step.retry(store.now().plusSeconds(5));
            }
            return Step.call(call);
        }
        if (stage.equals("ROUTE")) return route(job, p);
        if (stage.equals("AUDIT")) return audit(job, p);
        if (stage.equals("SCAN")) {
            var c = store.connection(job.owner());
            if (!c.enabled()) return done(job, "DISCONNECTED");
            if (!admit(c, job)) return Step.retry(store.now().plusSeconds(5));
            return Step.call(new Call(c, null, 0, 0, "REPOSITORY", c.path(), null, null));
        }
        var i = store.issue(job.owner(), true);
        var c = store.connection(i.connection());
        if (!c.enabled() && !observingSent(job)) {
            store.error(i.resource(), "DISCONNECTED");
            if (p.has("intent"))
                store.intentState(
                        UUID.fromString(p.path("intent").asText()), "ERROR", "DISCONNECTED");
            return done(job, "DISCONNECTED");
        }
        if (stage.equals("REFRESH") && p.path("seq").asLong() <= i.applied())
            return done(job, "COVERED");
        UUID intent = p.has("intent") ? UUID.fromString(p.path("intent").asText()) : null;
        if (intent != null) {
            var in = store.intent(intent);
            if (!in.active()) return done(job, "INTENT_TERMINAL");
            if (stage.equals("DISPATCH") && in.sent() != null) {
                verify(job, i, in, "UNKNOWN");
                return done(job, "VERIFY_RECOVERED");
            }
        }
        if (i.lease() != null && i.lease().isAfter(store.now()))
            return Step.retry(i.lease().plusMillis(10));
        if (!admit(c, job)) return Step.retry(store.now().plusSeconds(5));
        long generation = i.generation() + 1;
        store.jdbc()
                .update(
                        "UPDATE external_resource SET"
                            + " lane_generation=?,lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6))"
                            + " WHERE id=?",
                        generation,
                        bytes(i.resource()));
        if (stage.equals("DISPATCH")) {
            if (!client.writesEnabled()) {
                store.intentState(intent, "ERROR", "DISPATCH_DISABLED");
                release(i.resource());
                releaseInstallation(c, job);
                return done(job, "DISPATCH_DISABLED");
            }
            if (!authorized(store.intent(intent), i)) {
                store.intentState(intent, "ERROR", "AUTHORIZATION_CHANGED");
                release(i.resource());
                releaseInstallation(c, job);
                return done(job, "AUTHORIZATION_CHANGED");
            }
            store.intentState(intent, "PRECHECK", null);
        }
        String path = c.path() + "/issues/" + i.number();
        if (stage.equals("COMMENTS"))
            path += "/comments?per_page=100&page=" + p.path("page").asInt(1);
        if (!Set.of("REFRESH", "DISPATCH", "VERIFY", "COMMENTS").contains(stage))
            throw new IllegalArgumentException("Unknown GitHub stage");
        return Step.call(
                new Call(c, i.resource(), generation, i.requested(), stage, path, null, intent));
    }

    private boolean admit(GitHubStore.Connection c, WorkJobRepository.Job job) {
        store.jdbc()
                .update(
                        "INSERT IGNORE INTO github_installation_lane(installation_id) VALUES (?)",
                        c.installation());
        return store.jdbc()
                        .update(
                                "UPDATE github_installation_lane SET"
                                    + " owner_job=?,lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6))"
                                    + " WHERE installation_id=? AND"
                                    + " next_allowed_at<=UTC_TIMESTAMP(6) AND (owner_job IS NULL OR"
                                    + " lease_until<=UTC_TIMESTAMP(6) OR owner_job=?)",
                                bytes(job.id()),
                                c.installation(),
                                bytes(job.id()))
                == 1;
    }

    private void releaseInstallation(GitHubStore.Connection c, WorkJobRepository.Job job) {
        store.jdbc()
                .update(
                        "UPDATE github_installation_lane SET owner_job=NULL,lease_until=NULL WHERE"
                                + " installation_id=? AND owner_job=?",
                        c.installation(),
                        bytes(job.id()));
    }

    private Step audit(WorkJobRepository.Job job, JsonNode p) {
        var c = store.connection(job.owner());
        if (!c.enabled()) return done(job, "DISCONNECTED");
        UUID after = p.has("after") ? UUID.fromString(p.path("after").asText()) : new UUID(0, 0);
        var ids =
                store.jdbc()
                        .query(
                                "SELECT resource_id FROM github_issue WHERE connection_id=? AND"
                                        + " resource_id>? ORDER BY resource_id LIMIT 100",
                                (r, n) -> uuid(r.getBytes(1)),
                                bytes(c.id()),
                                bytes(after));
        for (UUID id : ids) {
            store.refresh(id);
            comments(job.project(), id, p.path("round").asText() + "/" + id, true);
        }
        if (ids.size() == 100) {
            UUID last = ids.get(ids.size() - 1);
            store.job(
                    job.project(),
                    job.owner(),
                    "github/audit/" + p.path("round").asText() + "/" + last,
                    Map.of("stage", "AUDIT", "round", p.path("round").asText(), "after", last),
                    store.now());
        }
        return done(job, "AUDIT_PAGE");
    }

    private Step route(WorkJobRepository.Job job, JsonNode payload) {
        UUID inbox = UUID.fromString(payload.path("inbox").asText());
        var c = store.connection(job.owner());
        var rows =
                store.jdbc()
                        .query(
                                "SELECT raw_body FROM webhook_inbox WHERE id=? AND connection_id=?",
                                (r, n) -> r.getBytes(1),
                                bytes(inbox),
                                bytes(c.id()));
        JsonNode event;
        try {
            event = store.mapper().readTree(rows.get(0));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid durable inbox", e);
        }
        UUID resource =
                store.resource(
                        c,
                        event.path("issue").path("id").asLong(),
                        event.path("issue").path("number").asLong(),
                        !event.has("comment") && event.path("action").asText().equals("opened") ? "LIVE" : "UNKNOWN");
        store.refresh(resource);
        if (event.has("comment")) comments(job.project(), resource, "event/" + inbox, false);
        store.jdbc().update("UPDATE webhook_inbox SET status='ROUTED' WHERE id=?", bytes(inbox));
        return done(job, "ROUTED");
    }

    public Object perform(Object request) {
        Call call = (Call) request;
        return client.request(
                call.connection().installation(),
                call.operation().equals("PATCH") ? "PATCH" : "GET",
                call.path(),
                call.patch());
    }

    public Step complete(WorkJobRepository.Job job, Object request, Object response) {
        var call = (Call) request;
        var reply = (GitHubClient.Reply) response;
        Step step = completeInternal(job, request, response);
        if (reply.retryAt() != null)
            store.jdbc()
                    .update(
                            "UPDATE github_installation_lane SET"
                                    + " next_allowed_at=GREATEST(next_allowed_at,?) WHERE"
                                    + " installation_id=?",
                            Utc.value(reply.retryAt()),
                            call.connection().installation());
        if (step.request() == null) releaseInstallation(call.connection(), job);
        return step;
    }

    private Step completeInternal(WorkJobRepository.Job job, Object request, Object response) {
        Call call = (Call) request;
        var reply = (GitHubClient.Reply) response;
        if (!store.writable(store.connection(call.connection().id()))
                && !call.operation().equals("PATCH")
                && !observingSent(job)) return stopped(job, "AUTHORIZATION_OR_ARCHIVE");
        if (call.operation().equals("EVIDENCE")) return evidence.complete(job, call, reply);
        if (call.resource() == null) return scanComplete(job, call, reply);
        var i = store.issue(call.resource(), true);
        if (i.generation() != call.generation()
                || i.lease() == null
                || !i.lease().isAfter(store.now()))
            throw new IllegalStateException("Resource lane lost; response cannot commit");
        store.jdbc()
                .update(
                        "UPDATE github_issue SET last_response=?,last_request_id=? WHERE"
                                + " resource_id=?",
                        store.json().write(reply.body()),
                        reply.requestId(),
                        bytes(i.resource()));
        if (call.operation().equals("PATCH")) {
            store.jdbc()
                    .update(
                            "UPDATE sync_intent SET provider_request_id=? WHERE id=?",
                            reply.requestId(),
                            bytes(call.intent()));
            var in = store.intent(call.intent());
            verify(job, i, in, reply.ok() ? "VERIFYING" : "UNKNOWN");
            release(i.resource());
            return done(job, "VERIFY_SCHEDULED");
        }
        if (!reply.ok()) return readFailure(job, call, i, reply);
        if (call.operation().equals("COMMENTS")) return commentsComplete(job, call, i, reply);
        // A newer accepted signal invalidates this captured response, even with a newer timestamp.
        if (i.requested() > call.captured()) {
            release(i.resource());
            return Step.retry(store.now().plusMillis(100));
        }
        IssueSnapshot snapshot;
        try {
            snapshot = IssueSnapshot.parse(reply.body());
        } catch (RuntimeException e) {
            store.error(i.resource(), "ERROR_UNSUPPORTED_REMOTE_STATE");
            if (call.intent() != null)
                store.intentState(call.intent(), "ERROR", "ERROR_UNSUPPORTED_REMOTE_STATE");
            release(i.resource());
            return done(job, "UNSUPPORTED");
        }
        if (snapshot.id() != i.remoteId() || snapshot.number() != i.number()) {
            store.error(i.resource(), "REMOTE_IDENTITY_MISMATCH");
            release(i.resource());
            return done(job, "IDENTITY_MISMATCH");
        }
        if (i.updatedAt() != null && snapshot.updatedAt().isBefore(i.updatedAt())) {
            release(i.resource());
            if (job.attempt() >= 5) {
                store.error(i.resource(), "STALE_PROVIDER_OBSERVATION");
                return done(job, "STALE_PROVIDER_OBSERVATION");
            }
            return Step.retryRead(store.now().plusSeconds(Math.min(60, 1L + job.attempt())));
        }
        if (call.operation().equals("DISPATCH")) return precheck(job, call, i, snapshot, reply);
        apply(job, i, snapshot, reply);
        if (call.intent() != null) settle(job, i, store.intent(call.intent()), snapshot);
        else {
            var pending = store.latest(i.resource());
            // A late observation may resolve an unknown/error episode, but never writes again.
            if (pending != null
                    && pending.sent() != null
                    && (pending.active() || pending.state().equals("ERROR")))
                settle(job, i, pending, snapshot);
        }
        release(i.resource());
        return done(job, "OBSERVED");
    }

    private Step precheck(
            WorkJobRepository.Job job,
            Call call,
            GitHubStore.Issue i,
            IssueSnapshot snapshot,
            GitHubClient.Reply reply) {
        var in = store.intent(call.intent());
        JsonNode patch = store.node(in.patch());
        boolean base = sameBase(in, snapshot);
        boolean revision =
                taskRepo.find(i.project(), i.task()).orElseThrow().revision() == in.taskRevision();
        apply(job, i, snapshot, reply);
        if (!authorized(in, i) || !revision) {
            store.intentState(in.id(), "CONFLICT", "CONTEXT_CHANGED");
            release(i.resource());
            return done(job, "CONFLICT");
        }
        if (snapshot.satisfies(patch)) {
            store.intentState(in.id(), "SATISFIED", null);
            release(i.resource());
            return done(job, "SATISFIED");
        }
        if (!base) {
            store.intentState(in.id(), "CONFLICT", "BASE_CHANGED");
            release(i.resource());
            return done(job, "CONFLICT");
        }
        store.jdbc()
                .update(
                        "UPDATE sync_intent SET send_started_at=UTC_TIMESTAMP(6) WHERE id=? AND"
                                + " send_started_at IS NULL",
                        bytes(in.id()));
        store.intentState(in.id(), "SEND_STARTED", null);
        // This Step is returned only after the executor commits SEND_STARTED.
        return Step.call(
                new Call(
                        call.connection(),
                        i.resource(),
                        call.generation(),
                        call.captured(),
                        "PATCH",
                        call.path(),
                        patch,
                        in.id()));
    }

    private boolean authorized(GitHubStore.Intent in, GitHubStore.Issue i) {
        try {
            var actor = new ActorContext(in.actor());
            var project = access.read(actor, i.project());
            var task = taskRepo.find(i.project(), i.task()).orElseThrow(ApiException::missing);
            return !project.archived()
                    && access.canEditTask(actor, task)
                    && (access.isMaintainer(actor, i.project())
                            || !store.node(in.patch()).has("title")
                                    && (!task.triageRequired()
                                            || !store.node(in.patch()).has("state"))
                                    && !"not_planned"
                                            .equals(
                                                    store.node(in.patch())
                                                            .path("state_reason")
                                                            .asText()))
                    && store.writable(store.connection(i.connection()));
        } catch (ApiException e) {
            return false;
        }
    }

    private boolean sameBase(GitHubStore.Intent in, IssueSnapshot snapshot) {
        JsonNode base = store.node(in.base());
        return base.path("shared").equals(store.mapper().valueToTree(snapshot.shared()))
                && base.path("updated_at").asText().equals(snapshot.updatedAt().toString());
    }

    private void apply(
            WorkJobRepository.Job job,
            GitHubStore.Issue i,
            IssueSnapshot snapshot,
            GitHubClient.Reply reply) {
        var c = store.connection(i.connection());
        Task old = i.task() == null ? null : taskRepo.find(i.project(), i.task()).orElseThrow();
        String previous = store.node(i.snapshot()).path("shared").path("state").asText(null);
        Task confirmed =
                tasks.applyGitHub(
                        i.project(),
                        i.task(),
                        c.actor(),
                        job.id(),
                        snapshot.title(),
                        snapshot.body(),
                        snapshot.status(old == null ? null : old.status(), previous));
        String data =
                store.json()
                        .write(
                                Map.of(
                                        "shared",
                                        snapshot.shared(),
                                        "updated_at",
                                        snapshot.updatedAt().toString(),
                                        "raw",
                                        reply.body(),
                                        "missing_reason_fallback",
                                        snapshot.state().equals("closed")
                                                && snapshot.reason() == null));
        boolean changed =
                i.snapshot() == null || !store.node(i.snapshot()).equals(store.node(data));
        var unresolved = store.latest(i.resource());
        boolean keepError =
                unresolved != null
                        && Set.of("ERROR", "CONFLICT").contains(unresolved.state())
                        && !snapshot.satisfies(store.node(unresolved.patch()));
        store.jdbc()
                .update(
                        "UPDATE github_issue SET"
                            + " task_id=?,snapshot=?,remote_updated_at=?,etag=?,sync_revision=sync_revision+?"
                                + (keepError
                                        ? ""
                                        : ",error_code=NULL,error_since=NULL,error_notified=FALSE")
                                + " WHERE resource_id=?",
                        bytes(confirmed.id()),
                        data,
                        Utc.value(snapshot.updatedAt()),
                        reply.etag(),
                        changed ? 1 : 0,
                        bytes(i.resource()));
        store.jdbc()
                .update(
                        "UPDATE external_resource SET"
                            + " applied_seq=requested_seq,observed_revision=observed_revision+1,confirmed_at=UTC_TIMESTAMP(6),availability='AVAILABLE',confirmed_metadata=?"
                            + " WHERE id=?",
                        data,
                        bytes(i.resource()));
        store.finishOperations(c.id());
        if (old == null) {
            comments(i.project(), i.resource(), "import/" + i.resource(), true);
            Instant created = null;
            try { created = Instant.parse(reply.body().path("created_at").asText()); }
            catch (java.time.format.DateTimeParseException ignored) { /* Missing provenance is manual-only. */ }
            String mode = store.jdbc().queryForObject("SELECT discovery_mode FROM github_issue WHERE resource_id=?", String.class, bytes(i.resource()));
            events.publishEvent(new GitHubIssueImported(confirmed, i.resource(), job.id(), created, mode));
        }
    }

    private void settle(
            WorkJobRepository.Job job,
            GitHubStore.Issue i,
            GitHubStore.Intent in,
            IssueSnapshot snapshot) {
        if (snapshot.satisfies(store.node(in.patch()))) {
            store.intentState(in.id(), "APPLIED", null);
            store.jdbc()
                    .update(
                            "UPDATE sync_intent SET attribution='OBSERVED_SATISFACTION' WHERE id=?",
                            bytes(in.id()));
        } else if (!sameBase(in, snapshot))
            store.intentState(in.id(), "CONFLICT", "REMOTE_CHANGED_OR_NOT_APPLIED");
        else if (in.sent() != null && !in.sent().plusSeconds(120).isAfter(store.now()))
            store.intentState(in.id(), "ERROR", "UNKNOWN_WRITE_OUTCOME");
        else verify(job, i, in, "UNKNOWN");
    }

    private void verify(
            WorkJobRepository.Job job, GitHubStore.Issue i, GitHubStore.Intent in, String state) {
        int next = in.checks() + 1;
        store.intentState(in.id(), state, null);
        store.jdbc()
                .update("UPDATE sync_intent SET verify_count=? WHERE id=?", next, bytes(in.id()));
        store.job(
                i.project(),
                i.resource(),
                "github/verify/" + in.id() + "/" + next,
                Map.of("stage", "VERIFY", "intent", in.id(), "check", next),
                store.now().plusSeconds(Math.min(15, 1L << Math.min(next - 1, 4))));
    }

    private Step readFailure(
            WorkJobRepository.Job job, Call call, GitHubStore.Issue i, GitHubClient.Reply reply) {
        release(i.resource());
        if (reply.retryableRead() && job.attempt() < 5) {
            if (call.intent() != null
                    && store.intent(call.intent()).sent() != null
                    && !store.intent(call.intent()).sent().plusSeconds(120).isAfter(store.now())) {
                store.intentState(call.intent(), "ERROR", "UNKNOWN_WRITE_OUTCOME");
                return done(job, "UNKNOWN_EXPIRED");
            }
            return Step.retryRead(retryAt(job, reply));
        }
        String code =
                reply.status() == 401 || reply.status() == 403 || reply.status() == 404
                        ? "EXTERNAL_UNAVAILABLE"
                        : "PROVIDER_READ_FAILED";
        store.error(i.resource(), code);
        store.jdbc()
                .update(
                        "UPDATE external_resource SET availability='UNAVAILABLE' WHERE id=?",
                        bytes(i.resource()));
        if (call.intent() != null) store.intentState(call.intent(), "ERROR", code);
        return done(job, code);
    }

    private Instant retryAt(WorkJobRepository.Job job, GitHubClient.Reply reply) {
        Instant minimum = store.now().plusSeconds(Math.min(60, 1L << Math.min(job.attempt(), 6)));
        return reply.retryAt() != null && reply.retryAt().isAfter(minimum)
                ? reply.retryAt()
                : minimum;
    }

    private Step scanComplete(WorkJobRepository.Job job, Call call, GitHubClient.Reply reply) {
        if (!reply.ok()) {
            if (reply.retryableRead() && job.attempt() < 5)
                return Step.retryRead(retryAt(job, reply));
            return done(job, "SCAN_FAILED_WATERMARK_UNCHANGED");
        }
        if (call.operation().equals("REPOSITORY")) {
            if (reply.body().path("id").asLong() != call.connection().repository())
                return done(job, "REPOSITORY_IDENTITY_MISMATCH");
            JsonNode p = store.node(job.payload());
            String since = p.path("since").asText("");
            String path =
                    call.connection().path()
                            + "/issues?state=all&sort=updated&direction=asc&per_page=100&page="
                            + p.path("page").asInt(1)
                            + (since.isEmpty()
                                    ? ""
                                    : "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8));
            return Step.call(new Call(call.connection(), null, 0, 0, "SCAN", path, null, null));
        }
        if (!reply.body().isArray()) return done(job, "INVALID_ISSUES_PAGE");
        store.jdbc()
                .update(
                        "UPDATE github_operation SET state='RUNNING',pages=pages+1 WHERE id=?",
                        bytes(UUID.fromString(store.node(job.payload()).path("round").asText())));
        for (JsonNode row : reply.body()) {
            if (row.has("pull_request")) continue;
            if (row.path("id").asLong() <= 0 || row.path("number").asLong() <= 0)
                throw new IllegalStateException("Invalid page identity");
            UUID resource =
                    store.resource(
                            call.connection(),
                            row.path("id").asLong(),
                            row.path("number").asLong(),
                            store.node(job.payload()).path("since").asText("").isEmpty() ? "BACKFILL" : "RECONCILE");
            store.refresh(resource);
            store.jdbc()
                    .update(
                            "INSERT INTO"
                                + " github_operation_resource(operation_id,resource_id,requested_seq)"
                                + " SELECT ?,id,requested_seq FROM external_resource WHERE id=? ON"
                                + " DUPLICATE KEY UPDATE requested_seq=VALUES(requested_seq)",
                            bytes(
                                    UUID.fromString(
                                            store.node(job.payload()).path("round").asText())),
                            bytes(resource));
        }
        JsonNode p = store.node(job.payload());
        if (reply.nextPage() || reply.body().size() == 100) {
            var next = p.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) next)
                    .put("page", p.path("page").asInt() + 1);
            store.job(
                    job.project(),
                    job.owner(),
                    "github/scan/" + p.path("round").asText() + "/" + next.path("page").asInt(),
                    store.mapper()
                            .convertValue(
                                    next,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {}),
                    store.now());
        } else {
            store.jdbc()
                    .update(
                            "UPDATE github_operation SET"
                                + " state='DISCOVERED',completed_at=UTC_TIMESTAMP(6) WHERE id=?",
                            bytes(UUID.fromString(p.path("round").asText())));
            Instant until = Instant.parse(p.path("until").asText());
            store.jdbc()
                    .update(
                            "UPDATE github_connection SET scan_watermark=CASE WHEN scan_watermark"
                                    + " IS NULL OR scan_watermark<? THEN ? ELSE scan_watermark END"
                                    + " WHERE id=?",
                            Utc.value(until),
                            Utc.value(until),
                            bytes(job.owner()));
        }
        store.finishOperations(call.connection().id());
        return done(job, "SCAN_PAGE");
    }

    private void comments(UUID project, UUID resource, String key, boolean initial) {
        store.job(
                project,
                resource,
                "github/comments/" + key + "/1",
                Map.of("stage", "COMMENTS", "page", 1, "round", key, "initial", initial),
                store.now());
    }

    private Step commentsComplete(
            WorkJobRepository.Job job, Call call, GitHubStore.Issue i, GitHubClient.Reply reply) {
        if (!reply.body().isArray()) {
            store.error(i.resource(), "INVALID_COMMENTS_PAGE");
            release(i.resource());
            return done(job, "INVALID_COMMENTS_PAGE");
        }
        try {
            for (JsonNode row : reply.body()) {
                if (row.path("id").asLong() <= 0 || !row.path("body").isTextual())
                    throw new IllegalArgumentException();
                Instant.parse(row.path("updated_at").asText());
            }
        } catch (RuntimeException malformed) {
            store.error(i.resource(), "INVALID_COMMENTS_PAGE");
            release(i.resource());
            return done(job, "INVALID_COMMENTS_PAGE");
        }
        JsonNode p = store.node(job.payload());
        String round = p.path("round").asText();
        long captured = p.has("captured") ? p.path("captured").asLong() : call.captured();
        comments.apply(i, reply.body(), round, p.path("initial").asBoolean());
        if (reply.nextPage() || reply.body().size() == 100) {
            int page = p.path("page").asInt() + 1;
            store.job(
                    job.project(),
                    job.owner(),
                    "github/comments/" + p.path("round").asText() + "/" + page,
                    Map.of(
                            "stage",
                            "COMMENTS",
                            "page",
                            page,
                            "round",
                            p.path("round").asText(),
                            "initial",
                            p.path("initial").asBoolean(),
                            "captured",
                            captured),
                    store.now());
        }
        if (!reply.nextPage() && reply.body().size() < 100) comments.finish(i, round, captured);
        release(i.resource());
        return done(job, "COMMENTS_PAGE");
    }

    private void release(UUID resource) {
        store.jdbc()
                .update(
                        "UPDATE external_resource SET lease_until=NULL WHERE id=?",
                        bytes(resource));
    }

    private boolean observingSent(WorkJobRepository.Job job) {
        var p = store.node(job.payload());
        String stage = p.path("stage").asText();
        if (!Set.of("VERIFY", "REFRESH", "DISPATCH").contains(stage)) return false;
        var in =
                p.has("intent")
                        ? store.intent(UUID.fromString(p.path("intent").asText()))
                        : store.latest(job.owner());
        return in != null && in.sent() != null && (in.active() || in.state().equals("ERROR"));
    }

    private Step stopped(WorkJobRepository.Job job, String reason) {
        var p = store.node(job.payload());
        if (Set.of("REFRESH", "DISPATCH", "VERIFY", "COMMENTS").contains(p.path("stage").asText()))
            store.error(job.owner(), reason);
        if (p.has("intent")) {
            var in = store.intent(UUID.fromString(p.path("intent").asText()));
            if (in.sent() == null) store.intentState(in.id(), "ERROR", reason);
        }
        return done(job, reason);
    }

    private Step done(WorkJobRepository.Job job, String result) {
        var p = store.node(job.payload());
        if (p.path("stage").asText().equals("SCAN") && !result.equals("SCAN_PAGE"))
            store.jdbc()
                    .update(
                            "UPDATE github_operation SET"
                                + " state='FAILED',error_code=?,completed_at=UTC_TIMESTAMP(6) WHERE"
                                + " id=?",
                            result,
                            bytes(UUID.fromString(p.path("round").asText())));
        return Step.done("github/" + job.id() + "/" + result);
    }
}
