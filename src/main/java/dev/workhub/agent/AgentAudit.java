package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import dev.workhub.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Per-request accounting. No answer/repair holds and no scheduling decisions. */
@Service
public class AgentAudit {
  private final JdbcTemplate db;
  private final Json json;
  private final AgentSettings c;
  private final dev.workhub.identity.IdentityRepository identities;
  @org.springframework.beans.factory.annotation.Autowired
  public AgentAudit(JdbcTemplate db,Json json,AgentSettings c,dev.workhub.identity.IdentityRepository identities) {
    this.db=db;this.json=json;this.c=c;this.identities=identities;
  }
  // Standalone accounting fixtures use the same guard without a Spring container.
  public AgentAudit(JdbcTemplate db,Json json,AgentSettings c) { this(db,json,c,new dev.workhub.identity.IdentityRepository(db)); }

  @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
  public int calls(UUID run) {
    return db.queryForObject("SELECT COUNT(*) FROM "+c.callTable()+" WHERE run_id=?",Integer.class,bytes(run));
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW)
  public void trace(UUID run,UUID attempt,String kind,Object payload) {
    db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) SELECT id,?,?,? FROM agent_run WHERE id=? AND NOT content_deleted",
        bytes(attempt),kind,json.write(payload),bytes(run));
  }
  private void current(UUID run,UUID attempt) {
    var row=db.queryForMap("SELECT status,attempt_token,stop_requested,content_deleted FROM agent_run WHERE id=? FOR UPDATE",bytes(run));
    if(Boolean.TRUE.equals(row.get("stop_requested")) || Boolean.TRUE.equals(row.get("content_deleted")) || !"RUNNING".equals(row.get("status")) || !Arrays.equals((byte[])row.get("attempt_token"),bytes(attempt)))
      throw new ApiException(409,"AGENT_ATTEMPT_SUPERSEDED","运行尝试已失效");
  }
  private void authorized(UUID run,UUID attempt) {
    // Admit a triage call under the same project barrier used by archive/task mutations.
    // Project before run matches the worker's lock order. This transaction ends BEFORE model I/O.
    var scope=db.queryForMap("SELECT project_id,actor_id,conversation_id,turn_id,triage_task_id,automatic_task_id FROM agent_run WHERE id=?",bytes(run));
    if(scope.get("conversation_id")!=null){
      UUID project=uuid((byte[])scope.get("project_id")),user=uuid((byte[])scope.get("actor_id"));
      UUID workspace=uuid(db.queryForObject("SELECT workspace_id FROM project WHERE id=?",byte[].class,bytes(project)));
      identities.lockWorkspace(workspace);
      if(!identities.activeUser(user,true)||!identities.activeWorkspaceMember(workspace,user,true))throw ApiException.forbidden();
      db.queryForList("SELECT id FROM project WHERE id=? FOR SHARE",bytes(project));
      if(identities.role(project,user).isEmpty())throw ApiException.forbidden();
      var conversation=db.queryForMap("SELECT owner_id,active_turn_id,deleted_at FROM agent_conversation WHERE id=? FOR SHARE",scope.get("conversation_id"));
      if(conversation.get("deleted_at")!=null||!Arrays.equals((byte[])conversation.get("owner_id"),bytes(user))||!Arrays.equals((byte[])conversation.get("active_turn_id"),(byte[])scope.get("turn_id")))throw new ApiException(409,"AGENT_ATTEMPT_SUPERSEDED","会话轮次已停止");
    }
    if(scope.get("triage_task_id")!=null) {
      UUID project=uuid((byte[])scope.get("project_id"));
      if(db.queryForObject("SELECT archived FROM project WHERE id=? FOR SHARE",Boolean.class,bytes(project)))
        throw new ApiException(409,"PROJECT_ARCHIVED","项目已归档，分诊分析停止");
      identities.requireTriageTask(project,uuid((byte[])scope.get("triage_task_id")));
      if(scope.get("automatic_task_id")!=null && db.queryForObject("SELECT automatic_stop_requested FROM agent_run WHERE id=?",Boolean.class,bytes(run)))
        throw new ApiException(503,"AUTOMATIC_TRIAGE_DISABLED","自动分诊已关闭，不再发出模型请求");
    }
    current(run,attempt);
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public void check(UUID run,UUID attempt) { c.available();authorized(run,attempt); }

  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public long reserveWait(UUID run,UUID attempt,long requested) {
    current(run,attempt);
    if(requested<1 || requested>15000)throw new IllegalArgumentException("Invalid wait allowance");
    long used=db.queryForObject("SELECT model_wait_millis FROM agent_run WHERE id=?",Long.class,bytes(run));
    long grant=Math.min(requested,30000-used);
    if(grant<=0)throw new ApiException(503,"MODEL_WAIT_BUDGET","模型等待累计额度耗尽");
    db.update("UPDATE agent_run SET model_wait_millis=model_wait_millis+? WHERE id=?",grant,bytes(run));
    return grant;
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public void finishWait(UUID run,UUID attempt,long grant,long elapsed) {
    if(grant<1 || grant>15000 || elapsed<0)throw new IllegalArgumentException("Invalid wait accounting");
    // A replaced attempt cannot refund its crash-conservative hold.
    db.update("UPDATE agent_run SET model_wait_millis=model_wait_millis-? WHERE id=? AND attempt_token=? AND model_wait_millis>=?",
        Math.max(0,grant-elapsed),bytes(run),bytes(attempt),grant);
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public boolean claimRetry(UUID run,UUID attempt) {
    current(run,attempt);
    return db.update("UPDATE agent_run SET model_retries=model_retries+1 WHERE id=? AND model_retries<2",bytes(run))==1;
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public UUID reserve(UUID run,UUID attempt,long input,long output) {
    return reserve(run,attempt,input,output,"ANSWER");
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public UUID reserve(UUID run,UUID attempt,long input,long output,String purpose) {
    if(!Set.of("ANSWER","COMPACTION").contains(purpose))throw new IllegalArgumentException();
    c.available();
    authorized(run,attempt);
    if(input<0 || output<0 || input>c.maxRequestInputBound || output>AgentSettings.MAX_OUTPUT)
      throw new ApiException(503,"AGENT_REQUEST_BUDGET","请求超过授权输入输出上界");
    if(calls(run)>=AgentSettings.MAX_ROUNDS)
      throw new ApiException(503,"AGENT_ROUND_BUDGET","跨尝试累计请求额度耗尽");
    String hash=Inputs.hash(json.write(new TreeMap<>(c.billingContract()))+":"+c.inputPrice+":"+c.outputPrice);
    // Duplicate-key UPDATE takes X directly; INSERT IGNORE followed by FOR UPDATE can deadlock on S-to-X upgrades.
    db.update("INSERT INTO "+c.budgetTable()+"(authorization_id,contract_hash,"+c.moneyColumn("limit")+") VALUES (?,?,?) ON DUPLICATE KEY UPDATE authorization_id=authorization_id",c.authorization,hash,c.budget);
    var budget=db.queryForMap("SELECT contract_hash,"+c.moneyColumn("limit")+" ceiling FROM "+c.budgetTable()+" WHERE authorization_id=? FOR UPDATE",c.authorization);
    if(!hash.equals(budget.get("contract_hash")) || ((Number)budget.get("ceiling")).longValue()!=c.budget)
      throw new ApiException(503,"MODEL_BUDGET_CONTRACT_CHANGED","授权已绑定其他计费合同");
    // An optional experiment cap is an authorization ceiling only. It never forces an answer.
    if(!c.fixture && c.campaignLimit>0 && c.campaignBaseline>=0
        && db.queryForObject("SELECT COUNT(*) FROM "+c.callTable()+" WHERE authorization_id=?",Long.class,c.authorization)>=c.campaignBaseline+c.campaignLimit)
      throw new ApiException(503,"AGENT_CAMPAIGN_LIMIT","授权请求次数已用完，未发出请求");
    long amount=Math.addExact(Math.multiplyExact(input,c.inputPrice),Math.multiplyExact(output,c.outputPrice));
    if(db.update("UPDATE "+c.budgetTable()+" SET "+c.moneyColumn("reserved")+"="+c.moneyColumn("reserved")+"+? WHERE authorization_id=? AND "+c.moneyColumn("reserved")+"+?<="+c.moneyColumn("limit"),amount,c.authorization,amount)!=1)
      throw new ApiException(503,"MODEL_COST_BUDGET","授权费用不足，未发出请求");
    UUID call=UUID.randomUUID();
    db.update("INSERT INTO "+c.callTable()+"(id,run_id,attempt_token,authorization_id,input_token_bound,output_token_bound,"+c.moneyColumn("reserved")+",purpose) VALUES (?,?,?,?,?,?,?,?)",
        bytes(call),bytes(run),bytes(attempt),c.authorization,input,output,amount,purpose);
    return call;
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public void toolAttempt(UUID run,UUID attempt,String name,String callId,String arguments) {
    current(run,attempt);
    db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,?,?)",bytes(run),bytes(attempt),"TOOL_ATTEMPT",
        json.write(Map.of("name",name,"callId",callId,"arguments",arguments)));
  }
  /** Finalize only with synchronous proof that transport never started: an unreached send boundary
   * or the adapter's explicit UnsentFailure. A network/response failure is not such proof.
   * COMPLETE means the local ledger is finalized; error_code distinguishes a zero-charge unsent call.
   * Crash recovery MUST NOT call this: a persisted RESERVED row alone proves nothing about transport.
   */
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public void finishUnsent(UUID call,long elapsed,String error) {
    var row=db.queryForMap("SELECT state,authorization_id,"+c.moneyColumn("reserved")+" reservation FROM "+c.callTable()+" WHERE id=? FOR UPDATE",bytes(call));
    if(!c.authorization.equals(row.get("authorization_id")))throw new IllegalArgumentException("Foreign authorization");
    if(!"RESERVED".equals(row.get("state")))return;
    db.update("UPDATE "+c.budgetTable()+" SET "+c.moneyColumn("reserved")+"="+c.moneyColumn("reserved")+"-? WHERE authorization_id=?",row.get("reservation"),c.authorization);
    db.update("UPDATE "+c.callTable()+" SET state='COMPLETE',input_tokens=0,output_tokens=0,"+c.moneyColumn("estimated")+"=0,elapsed_millis=?,error_code=? WHERE id=?",elapsed,error,bytes(call));
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
  public void finish(UUID call,Long input,Long output,long elapsed,String error) {
    var row=db.queryForMap("SELECT state,authorization_id,input_token_bound,output_token_bound,"+c.moneyColumn("reserved")+" reservation FROM "+c.callTable()+" WHERE id=? FOR UPDATE",bytes(call));
    if(!c.authorization.equals(row.get("authorization_id")))throw new IllegalArgumentException("Foreign authorization");
    if(!"RESERVED".equals(row.get("state")))return; // Duplicate completion cannot release twice.
    boolean known=error==null && input!=null && output!=null && input>0 && output>=0
        && input<=((Number)row.get("input_token_bound")).longValue()
        && output<=((Number)row.get("output_token_bound")).longValue();
    Long cost=known?Math.addExact(Math.multiplyExact(input,c.inputPrice),Math.multiplyExact(output,c.outputPrice)):null;
    if(known) {
      long release=((Number)row.get("reservation")).longValue()-cost;
      db.update("UPDATE "+c.budgetTable()+" SET "+c.moneyColumn("reserved")+"="+c.moneyColumn("reserved")+"-? WHERE authorization_id=?",release,c.authorization);
    }
    // Unknown calls keep their reservation. Historic calls/holds are never rewritten.
    db.update("UPDATE "+c.callTable()+" SET input_tokens=?,output_tokens=?,"+c.moneyColumn("estimated")+"=?,elapsed_millis=?,error_code=?,state=? WHERE id=?",
        known?input:null,known?output:null,cost,elapsed,known?null:Objects.toString(error,"MODEL_USAGE_INVALID"),known?"COMPLETE":"UNKNOWN",bytes(call));
  }
}
