package dev.workhub.messaging;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Repository
public class OutboxRepository {
    public record Envelope(
            UUID id,
            UUID jobId,
            long sequence,
            String destination,
            String handler,
            int schema,
            String wire,
            String hash,
            String disposition,
            UUID claim,
            int publishAttempt,
            Instant available) {}

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Envelope find(UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM outbox_message WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Envelope(
                                        read(r, "id"),
                                        read(r, "job_id"),
                                        r.getLong("dispatch_seq"),
                                        r.getString("destination"),
                                        r.getString("expected_handler"),
                                        r.getInt("schema_version"),
                                        r.getString("wire_payload"),
                                        r.getString("payload_hash"),
                                        r.getString("consume_disposition"),
                                        read(r, "claim_token"),
                                        r.getInt("publish_attempt"),
                                        Utc.read(r, "available_at")),
                        bytes(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(
            UUID id,
            UUID job,
            long seq,
            String destination,
            String handler,
            String wire,
            Instant available) {
        jdbc.update(
                "INSERT INTO"
                    + " outbox_message(id,job_id,dispatch_seq,destination,expected_handler,schema_version,payload,payload_hash,wire_payload,available_at)"
                    + " VALUES (?,?,?,?,?,1,?,?,?,?)",
                bytes(id),
                bytes(job),
                seq,
                destination,
                handler,
                wire,
                Inputs.hash(wire),
                wire,
                Utc.value(available));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Envelope claim(int leaseSeconds) {
        var ids =
                jdbc.query(
                        "SELECT id FROM outbox_message WHERE "
                                + "(publish_state='PENDING' AND available_at<=UTC_TIMESTAMP(6)) OR "
                                + "(publish_state='CLAIMED' AND claim_until<=UTC_TIMESTAMP(6)) "
                                + "ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (r, n) -> uuid(r.getBytes(1)));
        if (ids.isEmpty()) return null;
        UUID id = ids.get(0), token = UUID.randomUUID();
        jdbc.update(
                "UPDATE outbox_message SET publish_state='CLAIMED',claim_token=?,"
                    + "claim_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),publish_attempt=publish_attempt+1,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE id=?",
                bytes(token),
                leaseSeconds,
                bytes(id));
        return find(id, false);
    }

    public boolean confirmed(Envelope e) {
        return jdbc.update(
                        "UPDATE outbox_message SET"
                            + " publish_state='PUBLISHED',confirmed_at=UTC_TIMESTAMP(6),claim_token=NULL,claim_until=NULL,last_publish_error=NULL,updated_at=UTC_TIMESTAMP(6)"
                            + " WHERE id=? AND publish_state='CLAIMED' AND claim_token=?",
                        bytes(e.id()),
                        bytes(e.claim()))
                == 1;
    }

    public void publishFailed(Envelope e, String error, long delayMillis) {
        jdbc.update(
                "UPDATE outbox_message SET"
                    + " publish_state='PENDING',claim_token=NULL,claim_until=NULL,available_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)),last_publish_error=?,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE id=? AND publish_state='CLAIMED' AND claim_token=?",
                delayMillis * 1000,
                error,
                bytes(e.id()),
                bytes(e.claim()));
    }

    public void disposition(UUID id, String state, UUID next, String error) {
        jdbc.update(
                "UPDATE outbox_message SET"
                    + " consume_disposition=?,handled_at=UTC_TIMESTAMP(6),next_message_id=?,error_summary=?"
                    + " WHERE id=? AND consume_disposition='UNHANDLED'",
                state,
                bytes(next),
                error,
                bytes(id));
    }

    public UUID current(UUID job, long sequence) {
        var ids =
                jdbc.query(
                        "SELECT id FROM outbox_message WHERE job_id=? AND dispatch_seq=? ORDER BY"
                                + " id LIMIT 1",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(job),
                        sequence);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
