package dev.workhub.integration.github;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.*;

import dev.workhub.common.*;
import dev.workhub.messaging.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Repository
public class GitHubStore {
    public record Connection(
            UUID id,
            UUID project,
            long installation,
            long repository,
            String name,
            UUID actor,
            boolean enabled,
            Instant watermark) {
        public String path() {
            return "/repos/" + name;
        }
    }

    public record Issue(
            UUID resource,
            UUID project,
            UUID connection,
            long remoteId,
            long number,
            UUID task,
            long syncRevision,
            String snapshot,
            Instant updatedAt,
            long requested,
            long applied,
            long generation,
            Instant lease,
            String error,
            String availability,
            Instant confirmedAt) {}

    public record Intent(
            UUID id,
            UUID resource,
            UUID actor,
            long taskRevision,
            String base,
            String patch,
            String state,
            Instant sent,
            int checks,
            String error) {
        public boolean active() {
            return Set.of(
                            "QUEUED",
                            "PRECHECK",
                            "RETRY_WAIT",
                            "SEND_STARTED",
                            "VERIFYING",
                            "UNKNOWN")
                    .contains(state);
        }
    }

    final JdbcTemplate jdbc;
    final Json json;
    final ObjectMapper mapper;
    private final OutboxRepository outbox;

    public GitHubStore(JdbcTemplate jdbc, Json json, ObjectMapper mapper, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Json json() {
        return json;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode node(String text) {
        try {
            return text == null ? mapper.nullNode() : mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid persisted JSON", e);
        }
    }

    public static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof java.time.LocalDateTime t)
            return t.toInstant(java.time.ZoneOffset.UTC);
        throw new IllegalArgumentException("Expected database time");
    }

    public Instant now() {
        return jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(6)", (r, n) -> r.getTimestamp(1).toInstant());
    }

    public Connection connection(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM github_connection WHERE id=?",
                        (r, n) ->
                                new Connection(
                                        read(r, "id"),
                                        read(r, "project_id"),
                                        r.getLong("installation_id"),
                                        r.getLong("repository_id"),
                                        r.getString("repository_name"),
                                        read(r, "import_actor"),
                                        r.getBoolean("enabled"),
                                        Utc.read(r, "scan_watermark")),
                        bytes(id))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public void finishOperations(UUID connection) {
        jdbc.update(
                "UPDATE github_operation o SET state='SUCCEEDED',completed_at=UTC_TIMESTAMP(6)"
                    + " WHERE o.connection_id=? AND o.state='DISCOVERED' AND NOT EXISTS(SELECT 1"
                    + " FROM github_operation_resource x JOIN external_resource r ON"
                    + " r.id=x.resource_id WHERE x.operation_id=o.id AND"
                    + " r.applied_seq<x.requested_seq)",
                bytes(connection));
    }

    public boolean writable(Connection c) {
        return c.enabled()
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM project p JOIN project_member pm ON"
                                    + " pm.project_id=p.id JOIN workspace_member wm ON"
                                    + " wm.workspace_id=p.workspace_id AND wm.user_id=pm.user_id"
                                    + " JOIN app_user u ON u.id=pm.user_id JOIN"
                                    + " github_installation_grant g ON"
                                    + " g.workspace_id=p.workspace_id JOIN github_repository_grant"
                                    + " r ON r.installation_id=g.installation_id WHERE p.id=? AND"
                                    + " NOT p.archived AND pm.user_id=? AND pm.active AND wm.active"
                                    + " AND u.active AND g.installation_id=? AND r.repository_id=?",
                                Integer.class,
                                bytes(c.project()),
                                bytes(c.actor()),
                                c.installation(),
                                c.repository())
                        == 1;
    }

    public void lockProject(UUID project) {
        jdbc.queryForList(
                "SELECT w.id FROM workspace w WHERE w.id=(SELECT workspace_id FROM project WHERE"
                        + " id=?) FOR SHARE",
                bytes(project));
        jdbc.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", bytes(project));
    }

    public Issue issue(UUID id, boolean lock) {
        return jdbc
                .query(
                        "SELECT"
                            + " i.*,r.requested_seq,r.applied_seq,r.lane_generation,r.lease_until,r.availability,r.confirmed_at"
                            + " FROM github_issue i JOIN external_resource r ON r.id=i.resource_id"
                            + " WHERE i.resource_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Issue(
                                        read(r, "resource_id"),
                                        read(r, "project_id"),
                                        read(r, "connection_id"),
                                        r.getLong("remote_id"),
                                        r.getLong("issue_number"),
                                        read(r, "task_id"),
                                        r.getLong("sync_revision"),
                                        r.getString("snapshot"),
                                        Utc.read(r, "remote_updated_at"),
                                        r.getLong("requested_seq"),
                                        r.getLong("applied_seq"),
                                        r.getLong("lane_generation"),
                                        Utc.read(r, "lease_until"),
                                        r.getString("error_code"),
                                        r.getString("availability"),
                                        Utc.read(r, "confirmed_at")),
                        bytes(id))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public Intent intent(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM sync_intent WHERE id=?",
                        (r, n) ->
                                new Intent(
                                        read(r, "id"),
                                        read(r, "resource_id"),
                                        read(r, "actor_id"),
                                        r.getLong("task_revision"),
                                        r.getString("base_snapshot"),
                                        r.getString("desired_patch"),
                                        r.getString("state"),
                                        Utc.read(r, "send_started_at"),
                                        r.getInt("verify_count"),
                                        r.getString("error_code")),
                        bytes(id))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public Intent latest(UUID resource) {
        var ids =
                jdbc.query(
                        "SELECT id FROM sync_intent WHERE resource_id=? ORDER BY created_at DESC,id"
                                + " DESC LIMIT 1",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(resource));
        return ids.isEmpty() ? null : intent(ids.get(0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID job(UUID project, UUID owner, String key, Map<String, ?> payload, Instant due) {
        var found =
                jdbc.query(
                        "SELECT id FROM work_job WHERE job_key=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        key);
        if (!found.isEmpty()) return found.get(0);
        UUID id = UUID.randomUUID();
        String data = json.write(payload), hash = Inputs.hash(data);
        jdbc.update(
                "INSERT INTO"
                    + " work_job(id,kind,handler,job_key,project_id,owner_ref,payload,payload_hash,next_attempt_at)"
                    + " VALUES (?,'GITHUB_WORK','GITHUB_WORK',?,?,?,?,?,?)",
                bytes(id),
                key,
                bytes(project),
                bytes(owner),
                data,
                hash,
                Utc.value(due));
        String wire =
                json.write(
                        Map.of(
                                "jobId",
                                id,
                                "projectId",
                                project,
                                "kind",
                                "GITHUB_WORK",
                                "schemaVersion",
                                1,
                                "payloadHash",
                                hash));
        outbox.insert(UUID.randomUUID(), id, 0, "github.work", "GITHUB_WORK", wire, due);
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resource(Connection c, long remote, long number) {
        return resource(c, remote, number, "UNKNOWN");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resource(Connection c, long remote, long number, String discoveryMode) {
        var found =
                jdbc.query(
                        "SELECT resource_id FROM github_issue WHERE connection_id=? AND"
                                + " remote_id=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(c.id()),
                        remote);
        if (!found.isEmpty()) return found.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO external_resource(id,project_id,kind,provider_key,url) VALUES"
                        + " (?,?,'ISSUE',?,?)",
                bytes(id),
                bytes(c.project()),
                "github/" + c.repository() + "/" + remote,
                "https://github.com/" + c.name() + "/issues/" + number);
        jdbc.update(
                "INSERT INTO"
                    + " github_issue(resource_id,project_id,connection_id,remote_id,issue_number,discovery_mode)"
                    + " VALUES (?,?,?,?,?,?)",
                bytes(id),
                bytes(c.project()),
                bytes(c.id()),
                remote,
                number, discoveryMode);
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(UUID resource) {
        Issue i = issue(resource, true);
        long seq = i.requested() + 1;
        jdbc.update(
                "UPDATE external_resource SET requested_seq=? WHERE id=?", seq, bytes(resource));
        jdbc.update(
                "UPDATE github_issue SET sync_revision=sync_revision+1 WHERE resource_id=?",
                bytes(resource));
        job(
                i.project(),
                resource,
                "github/refresh/" + resource + "/" + seq,
                Map.of("stage", "REFRESH", "seq", seq),
                now());
    }

    public void intentState(UUID id, String state, String error) {
        Intent i = intent(id);
        if (Set.of("ERROR", "CONFLICT").contains(state))
            error(i.resource(), error == null ? state : error);
        jdbc.update(
                "UPDATE sync_intent SET state=?,error_code=?,updated_at=UTC_TIMESTAMP(6) WHERE"
                        + " id=?",
                state,
                error,
                bytes(id));
        jdbc.update(
                "UPDATE github_issue SET sync_revision=sync_revision+1 WHERE resource_id=?",
                bytes(i.resource()));
    }

    public void error(UUID resource, String error) {
        jdbc.update(
                "UPDATE github_issue SET"
                    + " error_code=?,error_since=COALESCE(error_since,UTC_TIMESTAMP(6)),sync_revision=sync_revision+1"
                    + " WHERE resource_id=?",
                error,
                bytes(resource));
    }
}
