package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Committed, authorized metadata only. Answers still require AgentRuns.get source validation. */
@Service
public class AgentProgress {
  private final ProjectAccess access;
  private final JdbcTemplate db;
  public AgentProgress(ProjectAccess access, JdbcTemplate db) { this.access=access;this.db=db; }
  public record Snapshot(UUID id, UUID taskId, String status, long sequence, String stage,
      String reason, String errorCode, Instant recordedAt, Instant observedAt) {
    public boolean terminal() { return !Set.of("QUEUED","RUNNING").contains(status); }
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=2)
  public Snapshot streamSnapshot(ActorContext actor,UUID project,UUID id,String sessionId) {
    if(sessionId==null || !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM SPRING_SESSION s JOIN app_user u ON CAST(u.login AS BINARY)=CAST(s.PRINCIPAL_NAME AS BINARY) WHERE s.SESSION_ID=? AND s.EXPIRY_TIME>? AND u.id=?)",Boolean.class,sessionId,System.currentTimeMillis(),bytes(actor.userId()))))
      throw ApiException.forbidden();
    return snapshot(actor,project,id);
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=2)
  public Snapshot snapshot(ActorContext actor, UUID project, UUID id) {
    access.read(actor,project);
    var rows=db.queryForList("SELECT actor_id,triage_task_id,attempt_token,status,error_code,content_deleted FROM agent_run WHERE id=? AND project_id=?",bytes(id),bytes(project));
    if(rows.isEmpty())throw ApiException.missing();
    var row=rows.get(0);if(Boolean.TRUE.equals(row.get("content_deleted")))throw ApiException.missing();UUID task=row.get("triage_task_id")==null?null:uuid((byte[])row.get("triage_task_id"));
    if(task!=null)access.maintainer(actor,project);
    else if(!Arrays.equals((byte[])row.get("actor_id"),bytes(actor.userId())))throw ApiException.missing();
    // Extract only a known wait reason in SQL; never load/export model input/output or tool bodies.
    var traces=db.query("SELECT id,attempt_token,kind,CASE WHEN kind='MODEL_WAIT' THEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.reason')) WHEN kind='MODEL_INPUT' THEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.purpose')) END AS reason,created_at FROM agent_trace WHERE run_id=? ORDER BY id DESC LIMIT 1",
        (r,n)->new Trace(r.getLong("id"),row.get("attempt_token")==null||Arrays.equals((byte[])row.get("attempt_token"),r.getBytes("attempt_token"))?r.getString("kind"):"",r.getString("reason"),Utc.read(r,"created_at")),bytes(id));
    var trace=traces.isEmpty()?new Trace(0,"",null,null):traces.get(0);
    String status=(String)row.get("status"),reason=null;
    String stage=switch(status) {
      case "QUEUED" -> "QUEUED";
      case "RUNNING" -> switch(trace.kind) {
        case "COMPACTION" -> "COMPACTION";
        case "MODEL_WAIT" -> "WAITING";
        case "MODEL_RETRY" -> "RETRYING";
        case "MODEL_INPUT" -> "COMPACTION".equals(trace.reason)?"COMPACTION":"MODEL_REQUEST";
        case "MODEL_OUTPUT","ANSWER_REJECTED","SOURCE_INVALIDATED" -> "CHECKING";
        case "TOOL_ATTEMPT","TOOL","TOOL_ERROR" -> "READING";
        default -> "RUNNING";
      };
      default -> "TERMINAL";
    };
    if("WAITING".equals(stage))reason=Set.of("RATE","COOLDOWN").contains(Objects.toString(trace.reason,""))?trace.reason:"ADMISSION";
    return new Snapshot(id,task,status,trace.id,stage,reason,(String)row.get("error_code"),trace.at,Instant.now());
  }
  private record Trace(long id,String kind,String reason,Instant at) {}
}
