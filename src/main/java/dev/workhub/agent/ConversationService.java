package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import static dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Account ownership is independent of project maintenance. All writes lock project before conversation. */
@Service
public class ConversationService {
  private final JdbcTemplate db; private final ProjectAccess access; private final AgentRuns runs;
  private final Json json; private final AgentTools tools;
  public ConversationService(JdbcTemplate db,ProjectAccess access,AgentRuns runs,Json json,AgentTools tools){this.db=db;this.access=access;this.runs=runs;this.json=json;this.tools=tools;}
  public record View(UUID id,UUID projectId,String projectName,String title,boolean archived,long historyRevision,
      long lastSeq,UUID activeTurnId,Instant lastActivityAt,boolean accessible,String cleanupState) {}
  public record Edit(String title,Boolean archived,long expectedHistoryRevision) {}
  public record Submit(String text,long expectedHistoryRevision) {}
  public record Receipt(UUID turnId,UUID runId) {}
  public record Turn(UUID id,long seq,UUID runId,String status,String errorCode,String text,Outcome result,
      String sourceState,Instant createdAt,List<UUID> attempts) {}
  public record Messages(List<Turn> items,Long nextBefore) {}
  private Map<String,Object> owned(ActorContext actor,UUID id,boolean lock,boolean deleted) {
    var row=db.queryForList("SELECT * FROM agent_conversation WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),bytes(id),bytes(actor.userId())).stream().findFirst().orElseThrow(ApiException::missing);
    if(!deleted && row.get("deleted_at")!=null)throw ApiException.missing();return row;
  }
  Map<String,Object> authorized(ActorContext actor,UUID id,boolean lock) {
    var row=owned(actor,id,false,false);UUID project=uuid((byte[])row.get("project_id"));
    if(lock){access.lockManagement(actor,project);row=owned(actor,id,true,false);}else access.read(actor,project);
    return row;
  }
  private String shortTitle(String text){return text.substring(0,text.offsetByCodePoints(0,Math.min(60,text.codePointCount(0,text.length()))));}
  private long number(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
  private View view(ActorContext actor,Map<String,Object> r) {
    UUID p=uuid((byte[])r.get("project_id"));var project=access.read(actor,p);
    return new View(uuid((byte[])r.get("id")),p,project.name(),(String)r.get("title"),Boolean.TRUE.equals(r.get("archived")),number(r,"history_revision"),number(r,"last_seq"),r.get("active_turn_id")==null?null:uuid((byte[])r.get("active_turn_id")),instant(r.get("last_activity_at")),true,(String)r.get("cleanup_state"));
  }
  private Instant instant(Object v){return v instanceof java.sql.Timestamp t?t.toInstant():((java.time.LocalDateTime)v).toInstant(java.time.ZoneOffset.UTC);}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public View get(ActorContext actor,UUID c){return view(actor,authorized(actor,c,false));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public AgentHistory.Page<View> list(ActorContext actor,UUID project,boolean archived,String query,String cursor,int limit) {
    AgentHistory.limit(limit);if(query.length()>120)throw ApiException.invalid("标题查询过长");var after=AgentHistory.parse(cursor);
    // Apply access in SQL BEFORE title search/pagination. Revoked titles cannot affect search results.
    String sql="SELECT c.* FROM agent_conversation c JOIN project p ON p.id=c.project_id JOIN project_member pm ON pm.project_id=p.id AND pm.user_id=c.owner_id AND pm.active JOIN workspace_member wm ON wm.workspace_id=p.workspace_id AND wm.user_id=c.owner_id AND wm.active JOIN app_user u ON u.id=c.owner_id AND u.active WHERE c.owner_id=? AND c.deleted_at IS NULL AND c.archived=?";
    var args=new ArrayList<Object>(List.of(bytes(actor.userId()),archived));
    if(project!=null){sql+=" AND c.project_id=?";args.add(bytes(project));}
    if(!query.isBlank()){sql+=" AND LOCATE(?,c.title)>0";args.add(query);}
    if(after!=null){sql+=" AND (c.last_activity_at<? OR (c.last_activity_at=? AND c.id<?))";var t=Utc.value(after.createdAt());args.addAll(List.of(t,t,bytes(after.id())));}
    args.add(limit+1);var rows=db.queryForList(sql+" ORDER BY c.last_activity_at DESC,c.id DESC LIMIT ?",args.toArray());
    var items=rows.stream().limit(limit).map(r->view(actor,r)).toList();var last=items.isEmpty()?null:items.get(items.size()-1);
    return new AgentHistory.Page<>(items,rows.size()>limit?new AgentHistory.Cursor(last.lastActivityAt(),last.id()).encode():null);
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<UUID> unavailable(ActorContext actor){
    return db.query("SELECT c.id FROM agent_conversation c WHERE c.owner_id=? AND c.deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM project p JOIN project_member pm ON pm.project_id=p.id AND pm.user_id=c.owner_id AND pm.active JOIN workspace_member wm ON wm.workspace_id=p.workspace_id AND wm.user_id=c.owner_id AND wm.active WHERE p.id=c.project_id) ORDER BY c.id LIMIT 100",(r,n)->read(r,"id"),bytes(actor.userId()));
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public int importLegacy(ActorContext actor){
    var rows=db.queryForList("SELECT id,project_id FROM agent_run WHERE actor_id=? AND conversation_id IS NULL AND triage_task_id IS NULL AND status NOT IN ('QUEUED','RUNNING') AND NOT content_deleted ORDER BY created_at,id LIMIT 50",bytes(actor.userId()));int imported=0;
    for(var r:rows){UUID p=uuid((byte[])r.get("project_id"));try{access.read(actor,p);}catch(ApiException e){continue;}migrate(actor,p,uuid((byte[])r.get("id")));imported++;}return imported;
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View create(ActorContext actor,UUID p){access.lockManagement(actor,p);UUID id=UUID.randomUUID();db.update("INSERT INTO agent_conversation(id,owner_id,project_id,title) VALUES (?,?,?,'新会话')",bytes(id),bytes(actor.userId()),bytes(p));return get(actor,id);}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View edit(ActorContext actor,UUID c,Edit edit){var row=authorized(actor,c,true);expected(row,edit.expectedHistoryRevision());
    String title=edit.title()==null?(String)row.get("title"):Inputs.text(edit.title(),"title",120);
    db.update("UPDATE agent_conversation SET title=?,archived=?,history_revision=history_revision+1 WHERE id=?",title,edit.archived()==null?row.get("archived"):edit.archived(),bytes(c));return get(actor,c);
  }
  private void expected(Map<String,Object> r,long revision){if(number(r,"history_revision")!=revision)throw new ApiException(409,"CONVERSATION_CONFLICT","会话已在其他窗口更新，请刷新后重试；草稿已保留");}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Receipt submit(ActorContext actor,UUID c,Submit request,String key,UUID retry) {
    var row=authorized(actor,c,true);Inputs.text(key,"Idempotency-Key",128);
    String text=retry==null?Inputs.text(request.text(),"text",2000):null;
    String hash=Inputs.hash(json.write(Arrays.asList(retry,text,request.expectedHistoryRevision())));
    var receipts=db.queryForList("SELECT * FROM agent_turn_receipt WHERE conversation_id=? AND request_key=?",bytes(c),key);
    if(!receipts.isEmpty()){var r=receipts.get(0);if(!hash.equals(r.get("request_hash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","该请求标识已用于其他内容");return new Receipt(uuid((byte[])r.get("turn_id")),uuid((byte[])r.get("run_id")));}
    expected(row,request.expectedHistoryRevision());
    if(Boolean.TRUE.equals(row.get("archived")))throw new ApiException(409,"CONVERSATION_ARCHIVED","请先恢复已归档会话");
    if(row.get("active_turn_id")!=null)throw new ApiException(409,"CONVERSATION_BUSY","此会话正在回答，请等待或停止当前轮次；草稿已保留");
    long seq=number(row,"last_seq");UUID turn=retry==null?UUID.randomUUID():retry;
    if(retry!=null){var t=db.queryForList("SELECT * FROM agent_turn WHERE id=? AND conversation_id=? AND seq=?",bytes(turn),bytes(c),seq).stream().findFirst().orElseThrow(ApiException::missing);
      if(!Set.of("FAILED","STALE","CANCELED").contains(t.get("status")))throw new ApiException(409,"TURN_NOT_RETRYABLE","只能重试末尾失败或取消的轮次");
      text=db.queryForObject("SELECT body FROM agent_message WHERE turn_id=? AND role='USER'",String.class,bytes(turn));
    }else{seq++;db.update("INSERT INTO agent_turn(id,conversation_id,seq,context_head_seq) VALUES (?,?,?,?)",bytes(turn),bytes(c),seq,seq-1);
      db.update("INSERT INTO agent_message(id,conversation_id,turn_id,role,body) VALUES (?,?,?,'USER',?)",bytes(UUID.randomUUID()),bytes(c),bytes(turn),text);}
    var run=runs.create(actor,uuid((byte[])row.get("project_id")),new AgentRuns.Request(text),"conversation/"+UUID.randomUUID());
    db.update("UPDATE agent_run SET conversation_id=?,turn_id=? WHERE id=?",bytes(c),bytes(turn),bytes(run.id()));
    db.update("UPDATE agent_turn SET active_run_id=?,status='QUEUED',error_code=NULL WHERE id=?",bytes(run.id()),bytes(turn));
    db.update("UPDATE agent_conversation SET active_turn_id=?,title=IF(last_seq=0,?,title),last_seq=?,history_revision=history_revision+1,last_activity_at=UTC_TIMESTAMP(6) WHERE id=?",bytes(turn),shortTitle(text),seq,bytes(c));
    db.update("INSERT INTO agent_turn_receipt VALUES (?,?,?,?,?)",bytes(c),key,hash,bytes(turn),bytes(run.id()));
    return new Receipt(turn,run.id());
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View cancel(ActorContext actor,UUID c,UUID t){var row=authorized(actor,c,true);
    if(Arrays.equals((byte[])row.get("active_turn_id"),bytes(t))){
      db.update("UPDATE agent_run SET stop_requested=TRUE,status='FAILED',error_code='CONVERSATION_CANCELED',completed_at=UTC_TIMESTAMP(6) WHERE conversation_id=? AND turn_id=? AND status IN ('QUEUED','RUNNING')",bytes(c),bytes(t));
      db.update("UPDATE agent_turn SET status='CANCELED',error_code='CONVERSATION_CANCELED' WHERE id=?",bytes(t));
      db.update("UPDATE agent_conversation SET active_turn_id=NULL,history_revision=history_revision+1 WHERE id=?",bytes(c));
    }return get(actor,c);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Map<String,String> delete(ActorContext actor,UUID c){var initial=owned(actor,c,false,true);
    // Deletion remains available after membership loss, but still follows the workspace/project barriers.
    UUID p=uuid((byte[])initial.get("project_id"));
    db.queryForList("SELECT w.id FROM workspace w JOIN project p ON p.workspace_id=w.id WHERE p.id=? FOR SHARE",bytes(p));
    db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE",bytes(p));owned(actor,c,true,true);
    db.update("UPDATE agent_conversation SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(6)),title='',active_turn_id=NULL,cleanup_state='CLEANING',history_revision=history_revision+1 WHERE id=?",bytes(c));
    db.update("UPDATE agent_run SET stop_requested=TRUE,content_deleted=TRUE,status='FAILED',error_code='CONVERSATION_DELETED',question='',result=NULL,object_bindings=NULL,context_manifest=NULL,completed_at=COALESCE(completed_at,UTC_TIMESTAMP(6)) WHERE conversation_id=?",bytes(c));
    db.update("DELETE k FROM agent_run_checkpoint k JOIN agent_run r ON r.id=k.run_id WHERE r.conversation_id=?",bytes(c));
    db.update("DELETE t FROM agent_trace t JOIN agent_run r ON r.id=t.run_id WHERE r.conversation_id=?",bytes(c));
    db.update("DELETE FROM agent_context_summary WHERE conversation_id=?",bytes(c));db.update("DELETE FROM agent_message WHERE conversation_id=?",bytes(c));
    db.update("DELETE FROM agent_turn_receipt WHERE conversation_id=?",bytes(c));
    db.update("UPDATE agent_turn SET status='CANCELED',error_code='CONVERSATION_DELETED' WHERE conversation_id=?",bytes(c));
    db.update("UPDATE agent_conversation SET cleanup_state='CLEANED' WHERE id=?",bytes(c));
    return Map.of("cleanupState","CLEANED","backupNotice","在线正文已清理；已有备份按部署保留政策过期，费用骨架保留。");
  }
  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public Messages messages(ActorContext actor,UUID c,Long before,int limit){var row=authorized(actor,c,false);AgentHistory.limit(limit);UUID p=uuid((byte[])row.get("project_id"));
    var values=db.queryForList("SELECT t.*,u.body question,a.body answer FROM agent_turn t JOIN agent_message u ON u.turn_id=t.id AND u.role='USER' LEFT JOIN agent_message a ON a.turn_id=t.id AND a.role='ASSISTANT' WHERE t.conversation_id=? AND t.seq<? ORDER BY t.seq DESC LIMIT ?",bytes(c),before==null?Long.MAX_VALUE:before,limit+1);
    var result=new ArrayList<Turn>();
    for(var r:values.stream().limit(limit).toList()){
      Outcome answer=r.get("answer")==null?null:json.read((String)r.get("answer"),Outcome.class);String state="CURRENT";
      if(answer!=null){state=tools.historyState(actor,p,answer.sources());if(state.equals("UNAVAILABLE"))answer=null;}
      UUID id=uuid((byte[])r.get("id"));var attempts=db.query("SELECT id FROM agent_run WHERE turn_id=? ORDER BY created_at,id",(rs,n)->read(rs,"id"),bytes(id));
      result.add(new Turn(id,number(r,"seq"),r.get("active_run_id")==null?null:uuid((byte[])r.get("active_run_id")),(String)r.get("status"),(String)r.get("error_code"),(String)r.get("question"),answer,state,instant(r.get("created_at")),attempts));
    }
    Collections.reverse(result);return new Messages(List.copyOf(result),values.size()>limit?result.get(0).seq():null);
  }
  /** Called by the existing worker INSIDE its project barrier transaction, before run locks. */
  public void lockRun(UUID run){db.queryForList("SELECT c.id FROM agent_conversation c JOIN agent_run r ON r.conversation_id=c.id WHERE r.id=? FOR UPDATE",bytes(run));}
  public boolean live(UUID run){return !Boolean.TRUE.equals(db.queryForObject("SELECT r.stop_requested OR r.content_deleted OR (r.conversation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM agent_conversation c JOIN agent_turn t ON t.id=r.turn_id WHERE c.id=r.conversation_id AND c.deleted_at IS NULL AND c.active_turn_id=t.id AND t.active_run_id=r.id)) FROM agent_run r WHERE r.id=?",Boolean.class,bytes(run)));}
  public void finish(UUID run,Outcome outcome){
    var rows=db.queryForList("SELECT conversation_id,turn_id FROM agent_run WHERE id=? AND conversation_id IS NOT NULL AND NOT stop_requested AND NOT content_deleted",bytes(run));if(rows.isEmpty())return;
    var r=rows.get(0);byte[] c=(byte[])r.get("conversation_id"),t=(byte[])r.get("turn_id");
    int changed=db.update("UPDATE agent_turn SET status=?,error_code=? WHERE id=? AND active_run_id=?",outcome.status(),outcome.errorCode(),t,bytes(run));if(changed==0)return;
    if(outcome.answer()!=null)db.update("INSERT INTO agent_message(id,conversation_id,turn_id,role,body) VALUES (?,?,?,'ASSISTANT',?) ON DUPLICATE KEY UPDATE body=VALUES(body)",bytes(UUID.randomUUID()),c,t,json.write(outcome));
    db.update("UPDATE agent_conversation SET active_turn_id=NULL,history_revision=history_revision+1,last_activity_at=UTC_TIMESTAMP(6) WHERE id=? AND active_turn_id=? AND deleted_at IS NULL",c,t);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View migrate(ActorContext actor,UUID p,UUID run){access.lockManagement(actor,p);var r=runs.accessible(actor,p,run);
    if(r.get("triage_task_id")!=null)throw ApiException.missing();if(r.get("conversation_id")!=null)return get(actor,uuid((byte[])r.get("conversation_id")));
    if(Set.of("QUEUED","RUNNING").contains(r.get("status")))throw new ApiException(409,"LEGACY_RUN_ACTIVE","请在原分析结束后导入会话");
    UUID c=UUID.randomUUID(),t=UUID.randomUUID();String q=(String)r.get("question");
    Object askedAt=r.get("created_at"),answeredAt=r.get("completed_at")==null?askedAt:r.get("completed_at");
    db.update("INSERT INTO agent_conversation(id,owner_id,project_id,title,last_seq,history_revision,created_at,last_activity_at) VALUES (?,?,?,?,1,1,?,?)",bytes(c),bytes(actor.userId()),bytes(p),shortTitle(q),askedAt,answeredAt);
    db.update("INSERT INTO agent_turn(id,conversation_id,seq,context_head_seq,active_run_id,status,error_code,created_at) VALUES (?,?,1,0,?,?,?,?)",bytes(t),bytes(c),bytes(run),r.get("status"),r.get("error_code"),askedAt);
    db.update("INSERT INTO agent_message(id,conversation_id,turn_id,role,body,created_at) VALUES (?,?,?,'USER',?,?)",bytes(UUID.randomUUID()),bytes(c),bytes(t),q,askedAt);
    if(r.get("result")!=null)db.update("INSERT INTO agent_message(id,conversation_id,turn_id,role,body,created_at) VALUES (?,?,?,'ASSISTANT',?,?)",bytes(UUID.randomUUID()),bytes(c),bytes(t),r.get("result"),answeredAt);
    db.update("UPDATE agent_run SET conversation_id=?,turn_id=? WHERE id=?",bytes(c),bytes(t),bytes(run));return get(actor,c);
  }
}
