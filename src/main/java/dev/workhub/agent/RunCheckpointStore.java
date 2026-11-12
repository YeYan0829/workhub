package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Only complete protocol boundaries are persisted. A response without a boundary is NOT replayed. */
@Service
public class RunCheckpointStore {
  @org.springframework.beans.factory.annotation.Autowired private TeamModels teams;
  private final JdbcTemplate db;private final Json json;private final ConversationService conversations;
  private final org.springframework.context.ApplicationEventPublisher events;
  public record Boundary(UUID run,String phase,int calls) {}
  private final ConversationContext context;private final AgentTools tools;private final AgentSettings settings;
  public RunCheckpointStore(JdbcTemplate db,Json json,ConversationService conversations,ConversationContext context,AgentTools tools,AgentSettings settings,org.springframework.context.ApplicationEventPublisher events){this.events=events;this.db=db;this.json=json;this.conversations=conversations;this.context=context;this.tools=tools;this.settings=settings;}
  record State(AgentConversation.Snapshot protocol,List<String> used,int rejectedAnswers) {}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  State restore(UUID run,UUID attempt,ActorContext actor,UUID project){
    var scope=context.scope(run);if(scope==null)return null;context.check(run,attempt,actor,project);conversations.authorized(actor,scope.conversation(),true);
    var rows=db.queryForList("SELECT * FROM agent_run_checkpoint WHERE run_id=?",bytes(run));
    int calls=count(run);
    long uncertain=db.queryForObject("SELECT COUNT(*) FROM "+teams.callTable(run)+" WHERE run_id=? AND state NOT IN ('COMPLETE','NOT_SENT')",Long.class,bytes(run));
    if(uncertain>0)throw new ApiException(503,"MODEL_RESULT_UNKNOWN","上次模型请求结果未知；不会自动重发，请明确重试");
    if(rows.isEmpty()){if(calls>0)throw lost();return null;}
    var r=rows.get(0);
    if(((Number)r.get("call_count")).intValue()!=calls)throw lost();
    if(((Number)r.get("format_version")).intValue()!=1||!Inputs.hash((String)r.get("payload")).equals(r.get("payload_hash")))throw new ApiException(503,"CHECKPOINT_INVALID","恢复记录格式不兼容或已损坏");
    var state=json.read((String)r.get("payload"),State.class);
    if(!tools.invalidSources(actor,project,state.protocol().sources()).isEmpty())throw new ApiException(409,"CHECKPOINT_SOURCE_CHANGED","恢复点来源已变化，请重试本轮");
    if(((Number)r.get("summary_version")).longValue()>0 && db.queryForObject("SELECT COUNT(*) FROM agent_context_summary WHERE conversation_id=? AND version=? AND valid",Integer.class,bytes(scope.conversation()),r.get("summary_version"))==0)
      throw new ApiException(409,"CHECKPOINT_SUMMARY_INVALID","恢复点摘要已失效");
    return state;
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  void save(UUID run,UUID attempt,ActorContext actor,AgentConversation protocol,List<String> used,int rejected){
    var scope=context.scope(run);if(scope==null)return;var c=conversations.authorized(actor,scope.conversation(),true);
    var r=db.queryForMap("SELECT * FROM agent_run WHERE id=? FOR UPDATE",bytes(run));
    if(!Arrays.equals((byte[])r.get("attempt_token"),bytes(attempt))||!"RUNNING".equals(r.get("status"))||Boolean.TRUE.equals(r.get("stop_requested"))||Boolean.TRUE.equals(r.get("content_deleted")))throw new ApiException(409,"AGENT_ATTEMPT_SUPERSEDED","运行已停止");
    int calls=count(run);events.publishEvent(new Boundary(run,"BEFORE",calls));
    String payload=json.write(new State(protocol.snapshot(),List.copyOf(used),rejected));
    // Registry and protocol are both bounded; never save an unusable partial checkpoint.
    if(payload.length()>2_000_000)throw new ApiException(503,"CHECKPOINT_LIMIT","恢复记录超过容量，原始消息保留");
    db.update("INSERT INTO agent_run_checkpoint(run_id,attempt_token,format_version,call_count,summary_version,history_revision,payload,payload_hash) VALUES (?,?,1,?,?,?,?,?) ON DUPLICATE KEY UPDATE attempt_token=VALUES(attempt_token),call_count=VALUES(call_count),summary_version=VALUES(summary_version),history_revision=VALUES(history_revision),payload=VALUES(payload),payload_hash=VALUES(payload_hash),created_at=UTC_TIMESTAMP(6)",bytes(run),bytes(attempt),count(run),c.get("summary_version"),c.get("history_revision"),payload,Inputs.hash(payload));
    db.update("UPDATE agent_run SET object_bindings=? WHERE id=?",json.write(protocol.bindings()),bytes(run));
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCommit(){events.publishEvent(new Boundary(run,"AFTER",calls));}});
  }
  private int count(UUID run){return db.queryForObject("SELECT COUNT(*) FROM "+teams.callTable(run)+" WHERE run_id=?",Integer.class,bytes(run));}
  private ApiException lost(){return new ApiException(503,"CHECKPOINT_RESPONSE_LOST","模型调用已记录但响应未形成安全恢复点；停止自动接续，可显式重试");}
}
