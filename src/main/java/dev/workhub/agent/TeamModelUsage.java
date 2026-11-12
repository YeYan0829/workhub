package dev.workhub.agent;

import static dev.workhub.agent.TeamModels.n;
import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable physical attempts and supplier-reported usage; never a monetary admission decision. */
@Service
public class TeamModelUsage {
  private final JdbcTemplate db;
  private final TeamModels teams;
  private final AgentSettings platform;

  public TeamModelUsage(JdbcTemplate db, TeamModels teams, AgentSettings platform) {
    this.db = db;
    this.teams = teams;
    this.platform = platform;
  }

  // AgentAudit owns the short transaction and authorization/run locks.
  public UUID begin(UUID run, UUID attempt, long input, long output, String purpose) {
    db.queryForList(
        "SELECT run_id FROM team_model_execution WHERE run_id=? FOR UPDATE", bytes(run));
    var r = teams.verify(run);
    if (input < 0
        || input > platform.maxRequestInputBound
        || output < 0
        || output > AgentSettings.MAX_OUTPUT)
      throw new ApiException(503, "AGENT_REQUEST_BUDGET", "请求超过输入输出上界");
    if (n(r, "call_count") >= n(r, "max_calls"))
      throw new ApiException(503, "AGENT_ROUND_BUDGET", "跨尝试累计请求次数耗尽");
    db.update("UPDATE team_model_execution SET call_count=call_count+1 WHERE run_id=?", bytes(run));
    UUID call = UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " team_model_usage(id,run_id,attempt_token,workspace_id,project_id,period_start,connection_version,provider,model,input_token_bound,output_token_bound,purpose)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        bytes(call),
        bytes(run),
        bytes(attempt),
        r.get("workspace_id"),
        r.get("project_id"),
        teams.period(),
        r.get("connection_version"),
        r.get("provider"),
        r.get("model"),
        input,
        output,
        purpose);
    return call;
  }

  public boolean owns(UUID call) {
    return db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM team_model_usage WHERE id=?)", Boolean.class, bytes(call));
  }

  public void finish(
      UUID call, Long input, Long output, long elapsed, String error, boolean unsent) {
    finish(call, input, output, elapsed, error, unsent, false);
  }

  public void finishHttpFailure(UUID call, long elapsed, String error) {
    // A received HTTP error is a known response, even though usage is absent. Prior retryable
    // errors must not make a later complete checkpoint appear to contain an unknown result.
    finish(call, null, null, elapsed, error, false, true);
  }

  private void finish(
      UUID call,
      Long input,
      Long output,
      long elapsed,
      String error,
      boolean unsent,
      boolean responseReceived) {
    var row = db.queryForMap("SELECT * FROM team_model_usage WHERE id=? FOR UPDATE", bytes(call));
    if (!"PENDING".equals(row.get("state"))) return;
    // Local byte/token bounds are not supplier billing rules. Preserve reported values even if
    // they exceed our request bound. Missing/negative counts remain NULL, never fabricated zero.
    Long in = unsent || input == null || input < 0 ? null : input;
    Long out = unsent || output == null || output < 0 ? null : output;
    String usage =
        unsent
            ? "NOT_APPLICABLE"
            : in != null && out != null
                ? "KNOWN"
                : in != null || out != null ? "PARTIAL" : "UNKNOWN";
    db.update(
        "UPDATE team_model_usage SET"
            + " state=?,usage_state=?,input_tokens=?,output_tokens=?,elapsed_millis=?,error_code=?"
            + " WHERE id=?",
        unsent ? "NOT_SENT" : error == null || responseReceived ? "COMPLETE" : "UNKNOWN",
        usage,
        in,
        out,
        elapsed,
        error,
        bytes(call));
  }
}
