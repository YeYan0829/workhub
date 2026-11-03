package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.messaging.OutboxRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Registers source metadata and future index work in the caller's transaction. No worker or network
 * call.
 */
@Repository
public class SourceObligations {
    public enum Owner {
        TASK,
        COMMENT,
        DOCUMENT,
        GITHUB_COMMENT
    }

    private final JdbcTemplate jdbc;
    private final Json json;
    private final OutboxRepository outbox;

    public SourceObligations(JdbcTemplate jdbc, Json json, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(UUID project, UUID owner, Owner kind, String body, String canonicalUrl) {
        String column =
                switch (kind) {
                    case TASK -> "task_id";
                    case COMMENT -> "comment_id";
                    case DOCUMENT -> "document_id";
                    case GITHUB_COMMENT -> "github_comment_id";
                };
        var ids =
                jdbc.query(
                        "SELECT id FROM knowledge_source WHERE project_id=? AND " + column + "=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(project),
                        bytes(owner));
        UUID source = ids.isEmpty() ? UUID.randomUUID() : ids.get(0);
        if (ids.isEmpty())
            jdbc.update(
                    "INSERT INTO knowledge_source(id,project_id,"
                            + column
                            + ",source_revision,body_hash,canonical_url) VALUES (?,?,?,0,?,?)",
                    bytes(source),
                    bytes(project),
                    bytes(owner),
                    Inputs.hash(body),
                    canonicalUrl);
        else
            jdbc.update(
                    "UPDATE knowledge_source SET source_revision=source_revision+1,body_hash=?"
                            + " WHERE project_id=? AND id=?",
                    Inputs.hash(body),
                    bytes(project),
                    bytes(source));
        long revision =
                jdbc.queryForObject(
                        "SELECT source_revision FROM knowledge_source WHERE id=?",
                        Long.class,
                        bytes(source));
        UUID job = UUID.randomUUID(), message = UUID.randomUUID();
        String payload = json.write(Map.of("sourceId", source, "sourceRevision", revision));
        String hash = Inputs.hash(payload);
        jdbc.update(
                "INSERT INTO"
                    + " work_job(id,kind,handler,job_key,project_id,owner_ref,payload,payload_hash)"
                    + " VALUES (?,'INDEX_TEXT','INDEX_TEXT',?,?,?,?,?)",
                bytes(job),
                "index/" + source + "/" + revision,
                bytes(project),
                bytes(source),
                payload,
                hash);
        String envelope =
                json.write(
                        Map.of(
                                "jobId",
                                job,
                                "projectId",
                                project,
                                "kind",
                                "INDEX_TEXT",
                                "schemaVersion",
                                1,
                                "payloadHash",
                                hash));
        outbox.insert(
                message,
                job,
                0,
                "knowledge.index",
                "INDEX_TEXT",
                envelope,
                java.time.Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivate(UUID project, UUID owner, Owner kind) {
        String column = switch(kind) {
            case TASK -> "task_id";
            case COMMENT -> "comment_id";
            case DOCUMENT -> "document_id";
            case GITHUB_COMMENT -> "github_comment_id";
        };
        jdbc.update("UPDATE knowledge_source SET active=FALSE WHERE project_id=? AND "+column+"=?", bytes(project), bytes(owner));
    }
}
