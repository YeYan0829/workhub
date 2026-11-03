package dev.workhub.messaging;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class BusinessJobs {
    private final JdbcTemplate db;
    private final Json json;
    private final OutboxRepository outbox;

    public BusinessJobs(JdbcTemplate db, Json json, OutboxRepository outbox) {
        this.db = db;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String kind, UUID project, UUID owner, String key, Map<String, ?> payload) {
        var ids = db.query("SELECT id FROM work_job WHERE job_key=?", (r, n) -> read(r, "id"), key);
        if (!ids.isEmpty()) return ids.get(0);
        UUID id = UUID.randomUUID();
        String data = json.write(payload), hash = Inputs.hash(data);
        db.update(
                "INSERT INTO"
                    + " work_job(id,kind,handler,job_key,project_id,owner_ref,payload,payload_hash)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                bytes(id),
                kind,
                kind,
                key,
                bytes(project),
                bytes(owner),
                data,
                hash);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("jobId", id);
        wire.put("projectId", project);
        wire.put("kind", kind);
        wire.put("schemaVersion", 1);
        wire.put("payloadHash", hash);
        outbox.insert(
                UUID.randomUUID(),
                id,
                0,
                kind.equals("RUN_AGENT") ? "agent.read" : kind.equals("EMBED_CHUNK") ? "knowledge.embedding" : Set.of("INDEX_TEXT", "PUBLISH_TEXT", "BUILD_VECTOR").contains(kind) ? "knowledge.index" : "business.work",
                kind,
                json.write(wire),
                java.time.Instant.now());
        return id;
    }
}
