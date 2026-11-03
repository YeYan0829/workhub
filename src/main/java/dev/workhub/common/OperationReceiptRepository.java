package dev.workhub.common;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class OperationReceiptRepository {
    private final JdbcTemplate jdbc;

    public OperationReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Receipt(String hash, UUID id) {}

    public Optional<Receipt> find(UUID actor, String key) {
        return jdbc
                .query(
                        "SELECT request_hash,result_id FROM operation_receipt WHERE actor_id=? AND"
                                + " request_key=?",
                        (r, n) -> new Receipt(r.getString(1), uuid(r.getBytes(2))),
                        bytes(actor),
                        key)
                .stream()
                .findFirst();
    }

    public void insert(UUID actor, String key, UUID project, String hash, UUID result) {
        jdbc.update(
                "INSERT INTO"
                    + " operation_receipt(actor_id,request_key,project_id,request_hash,result_id)"
                    + " VALUES (?,?,?,?,?)",
                bytes(actor),
                key,
                bytes(project),
                hash,
                bytes(result));
    }
}
