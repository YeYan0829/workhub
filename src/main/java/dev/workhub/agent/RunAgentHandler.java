package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import static dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RunAgentHandler implements RemoteWorkHandler {
  private final ConversationService conversations;
  private final AgentRuns runs;private final AgentEngine engine;private final AgentTools tools;
  private final ProjectAccess access;private final JdbcTemplate db;private final Json json;private final AgentSettings c;
  public RunAgentHandler(AgentRuns runs,AgentEngine engine,AgentTools tools,ProjectAccess access,JdbcTemplate db,Json json,AgentSettings c,ConversationService conversations){this.conversations=conversations;this.runs=runs;this.engine=engine;this.tools=tools;this.access=access;this.db=db;this.json=json;this.c=c;}
  public String kind(){return "RUN_AGENT";}
  @Override public int executionLeaseSeconds(){return (AgentSettings.MAX_MILLIS+999)/1000+30;}
  // Read analysis may be restarted after process loss. Paid reservations remain cumulative across attempts.
  public record Call(UUID run,UUID attempt,ActorContext actor,UUID project,long revision,String question,java.time.Instant deadline,UUID triageTask,TriageStrategy strategy) {}
  public void lockBusiness(WorkJobRepository.Job job) {
    var row=db.queryForMap("SELECT actor_id FROM agent_run WHERE id=? AND project_id=?",bytes(job.owner()),bytes(job.project()));
    // Authorization failures are made durable in prepare/complete, not thrown before execution lease.
    var p=db.queryForObject("SELECT workspace_id FROM project WHERE id=?",(r,n)->read(r,"workspace_id"),bytes(job.project()));
    db.queryForList("SELECT id FROM workspace WHERE id=? FOR SHARE",bytes(p));
    db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE",bytes(job.project()));
  }
  public Step prepare(WorkJobRepository.Job job) {
    var payload=json.read(job.payload(),Map.class);
    if(payload.size()!=1||!job.owner().toString().equals(payload.get("runId")))throw new WorkFailure(false,"INVALID_AGENT_JOB");
    conversations.lockRun(job.owner());
    var row=db.queryForMap("SELECT * FROM agent_run WHERE id=? FOR UPDATE",bytes(job.owner()));
    if(!Set.of("QUEUED","RUNNING").contains(row.get("status")))return Step.done("agent/"+job.owner());
    UUID requestedBy=uuid((byte[])row.get("actor_id"));
    // The original human remains in the audit row; project triage does not impersonate a successor.
    var actor=row.get("triage_task_id")==null?new ActorContext(requestedBy)
        :ActorContext.projectAnalysis(requestedBy,job.project(),uuid((byte[])row.get("triage_task_id")));
    try {
      if(Boolean.TRUE.equals(row.get("automatic_stop_requested")))throw new ApiException(503,"AUTOMATIC_TRIAGE_DISABLED","自动分诊已关闭");
      if("QUEUED".equals(row.get("status")) && db.queryForObject("SELECT created_at<=UTC_TIMESTAMP(6)-INTERVAL 600 SECOND FROM agent_run WHERE id=?",Boolean.class,bytes(job.owner())))
        throw new ApiException(503,"AGENT_QUEUE_TIMEOUT","排队超过十分钟，请重新发起分析");
      runs.available(job.project());runs.verify(job.owner());access.read(actor,job.project());
      if(c.legacyMode && !json.read((String)row.get("model_contract"),Map.class).equals((row.get("triage_task_id")==null?runs.contract(false):runs.contract(row.get("triage_strategy")==null?null:TriageStrategy.valueOf((String)row.get("triage_strategy"))))))throw new ApiException(503,"GLM_CONTRACT_CHANGED","模型配置已变化，请重新提问");
      if(!c.legacyMode && db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state NOT IN ('COMPLETE','NOT_SENT')",Long.class,bytes(job.owner()))>0)throw new ApiException(503,"MODEL_RESULT_UNKNOWN","旧调用结果未知，停止自动恢复，请重新发起");
      long revision=((Number)row.get("business_revision")).longValue();tools.validate(actor,job.project(),revision,List.of());
      // All admissions hold the project lock. Waiting does not start the model deadline or reserve money.
      if("QUEUED".equals(row.get("status")) && db.queryForObject(
          "SELECT COUNT(*) FROM agent_run WHERE project_id=? AND status='RUNNING'",Long.class,bytes(job.project()))>=3)
        return Step.retry(java.time.Instant.now().plusSeconds(15));
      var starts=db.queryForList("SELECT payload FROM agent_trace WHERE run_id=? AND kind='START' ORDER BY id LIMIT 1",bytes(job.owner()));
      java.time.Instant deadline;
      if(starts.isEmpty()) {
        if(row.get("automatic_task_id")!=null) {
          // Submission may wait in the queue while the author adds details. Freeze the basis
          // when analysis actually starts; later edits still invalidate the proposal.
          var target=db.queryForMap("SELECT revision,discussion_revision FROM task WHERE id=? AND project_id=?",row.get("triage_task_id"),bytes(job.project()));
          revision=db.queryForObject("SELECT business_revision FROM project WHERE id=?",Long.class,bytes(job.project()));
          db.update("UPDATE agent_run SET triage_task_revision=?,triage_discussion_revision=?,business_revision=? WHERE id=?",
              target.get("revision"),target.get("discussion_revision"),revision,bytes(job.owner()));
        }
        deadline=java.time.Instant.now().plusMillis(runs.maxMillis(job.owner()));
        db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,?,?)",bytes(job.owner()),bytes(UUID.randomUUID()),"START",json.write(Map.of("deadlineAt",deadline)));
      } else deadline=java.time.Instant.parse(json.read((String)starts.get(0).get("payload"),Map.class).get("deadlineAt").toString());
      if(!deadline.isAfter(java.time.Instant.now()))throw new ApiException(503,"AGENT_TIMEOUT","累计分析时间预算耗尽");
      UUID attempt=UUID.randomUUID();
      db.update("UPDATE agent_run SET status='RUNNING',attempt_token=? WHERE id=?",bytes(attempt),bytes(job.owner()));
      return Step.call(new Call(job.owner(),attempt,actor,job.project(),revision,(String)row.get("question"),deadline,row.get("triage_task_id")==null?null:uuid((byte[])row.get("triage_task_id")),row.get("triage_strategy")==null?null:TriageStrategy.valueOf((String)row.get("triage_strategy"))));
    }catch(ApiException e){fail(job.owner(),e.status==409?"STALE":"FAILED",e.code);return Step.done("agent/"+job.owner());}
  }
  public Object perform(Object request) {
    var call=(Call)request;
    var executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"project-agent-readonly");t.setDaemon(true);return t;});
    var future=executor.submit(()->engine.run(call.run(),call.attempt(),call.actor(),call.project(),call.revision(),call.question(),Math.max(0,call.deadline().toEpochMilli()-System.currentTimeMillis()),call.triageTask(),call.strategy()));
    try{return future.get(Math.max(1,call.deadline().toEpochMilli()-System.currentTimeMillis()),TimeUnit.MILLISECONDS);}
    catch(InterruptedException e){Thread.currentThread().interrupt();return failed("AGENT_INTERRUPTED");}
    catch(TimeoutException e){return failed("AGENT_TIMEOUT");}
    catch(ExecutionException e){return failed("AGENT_INTERNAL_ERROR");}
    finally{future.cancel(true);executor.shutdownNow();}
  }
  public Step complete(WorkJobRepository.Job job,Object request,Object response) {
    var call=(Call)request;var result=(Outcome)response;
    conversations.lockRun(call.run());
    if(!conversations.live(call.run()))return Step.done("agent/stopped/"+job.owner());
    var row=db.queryForMap("SELECT status,attempt_token,automatic_stop_requested FROM agent_run WHERE id=? FOR UPDATE",bytes(call.run()));
    if(!"RUNNING".equals(row.get("status")) || !Arrays.equals((byte[])row.get("attempt_token"),bytes(call.attempt())))return Step.done("agent/obsolete/"+job.owner());
    if(Boolean.TRUE.equals(row.get("automatic_stop_requested")))result=failed("AUTOMATIC_TRIAGE_DISABLED");
    if(!call.deadline().isAfter(java.time.Instant.now()) && result.answer()!=null)result=failed("AGENT_TIMEOUT");
    try{access.read(call.actor(),call.project());tools.validate(call.actor(),call.project(),call.revision(),result.sources());}
    catch(ApiException e){fail(call.run(),e.status==409?"STALE":"FAILED",e.code);return Step.done("agent/"+job.owner());}
    if(!call.deadline().isAfter(java.time.Instant.now()) && result.answer()!=null)result=failed("AGENT_TIMEOUT");
    db.update("UPDATE agent_run SET status=?,result=?,error_code=?,completed_at=UTC_TIMESTAMP(6) WHERE id=? AND attempt_token=?",result.status(),json.write(result),result.errorCode(),bytes(call.run()),bytes(call.attempt()));
    conversations.finish(call.run(),result);
    return Step.done("agent/"+job.owner());
  }
  public void terminalFailure(WorkJobRepository.Job job,String code){fail(job.owner(),"FAILED",code);}
  private void fail(UUID id,String status,String code){conversations.lockRun(id);if(!conversations.live(id))return;db.update("UPDATE agent_run SET status=?,result=NULL,error_code=?,completed_at=UTC_TIMESTAMP(6) WHERE id=?",status,code,bytes(id));conversations.finish(id,new Outcome(status,null,List.of(),List.of(),0,0,0,0,code));}
  private Outcome failed(String code){return new Outcome("FAILED",null,List.of(),List.of(),0,0,0,AgentSettings.MAX_MILLIS,code);}
}
