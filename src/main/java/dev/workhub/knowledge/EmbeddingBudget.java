package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Inputs;
import dev.workhub.messaging.WorkFailure;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "workhub.embedding.enabled", havingValue = "true")
public class EmbeddingBudget {
  private final JdbcTemplate db;
  private final DenseConfiguration c;

  public EmbeddingBudget(JdbcTemplate db, DenseConfiguration c) {
    this.db = db;
    this.c = c;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID reserve(UUID project, String purpose, String text, int upperTokens) {
    if (!c.fixture && !c.paidApproved) throw new WorkFailure(false, "PAID_EMBEDDING_NOT_APPROVED");
    db.update(
        "INSERT IGNORE INTO embedding_budget(run_id,limit_nanousd) VALUES (?,?)",
        c.runId,
        c.limitNanoUsd);
    long limit =
        db.queryForObject(
            "SELECT limit_nanousd FROM embedding_budget WHERE run_id=?", Long.class, c.runId);
    if (limit != c.limitNanoUsd) throw new WorkFailure(false, "EMBEDDING_BUDGET_ID_CONFLICT");
    long reserve = Math.multiplyExact(upperTokens, c.priceNanoUsdPerToken);
    if (db.update(
            "UPDATE embedding_budget SET reserved_nanousd=reserved_nanousd+? WHERE run_id=? AND"
                + " reserved_nanousd+?<=limit_nanousd",
            reserve,
            c.runId,
            reserve)
        != 1) throw new WorkFailure(false, "EMBEDDING_BUDGET_EXHAUSTED");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " embedding_call(id,run_id,config_id,project_id,purpose,input_hash,reserved_nanousd)"
            + " VALUES (?,?,?,?,?,?,?)",
        bytes(id),
        c.runId,
        c.id,
        bytes(project),
        purpose,
        Inputs.hash(text),
        reserve);
    return id;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(UUID id, Long tokens, long millis, String error) {
    // Reservations are never refunded automatically: unknown or repeated calls remain budgeted.
    db.update(
        "UPDATE embedding_call SET"
            + " state=?,reported_tokens=?,reported_nanousd=?,duration_ms=?,error_code=? WHERE id=?"
            + " AND state='RESERVED'",
        error == null ? "COMPLETE" : tokens == null ? "UNKNOWN" : "ERROR",
        tokens,
        tokens == null ? null : Math.multiplyExact(tokens, c.priceNanoUsdPerToken),
        millis,
        error,
        bytes(id));
  }
}
