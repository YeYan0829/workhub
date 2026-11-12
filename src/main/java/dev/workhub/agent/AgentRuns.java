package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import static dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.BusinessJobs;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AgentRuns {
  @org.springframework.beans.factory.annotation.Autowired private TeamModels teams;
  public void available(UUID project){teams.available(project);}
  public void verify(UUID run){teams.verifyMode(run);if(!teams.legacy())teams.verify(run);}
  public int maxMillis(UUID run){return teams.legacy()?AgentSettings.MAX_MILLIS:teams.maxMillis(run);}
  private final ProjectAccess access;private final AgentSettings c;private final JdbcTemplate db;
  private final Json json;private final BusinessJobs jobs;private final OperationReceipts receipts;private final AgentTools tools;
  public AgentRuns(ProjectAccess access,AgentSettings c,JdbcTemplate db,Json json,BusinessJobs jobs,OperationReceipts receipts,AgentTools tools){this.access=access;this.c=c;this.db=db;this.json=json;this.jobs=jobs;this.receipts=receipts;this.tools=tools;}
  @Transactional(readOnly=true)
  public Map<String,Object> availability(ActorContext actor,UUID project) {
    access.read(actor,project);
    try { teams.available(project); return Map.of("enabled",true); }
    catch(ApiException e) { return Map.of("enabled",false,"reason",e.code); }
  }
  public Map<String,Object> contract(boolean triage) {
    if(!triage)return c.contract();
    var value=new TreeMap<String,Object>(c.contract());
    value.put("runtimeVersion","single-task-triage-1");
    value.put("responseFormat","native-tools-or-json-triage");
    return Map.copyOf(value);
  }
  public Map<String,Object> contract(TriageStrategy strategy) {
    if(strategy==null)return contract(true);
    var value=new TreeMap<String,Object>(contract(true));
    value.put("runtimeVersion","triage-investigation-1");value.put("strategy",strategy.name());
    value.put("investigationToolLimit",40);value.put("guidedDiscoveryCalls",3);
    return Map.copyOf(value);
  }
  public record Request(String question) {}
  public record TriageRequest(String question,TriageStrategy strategy) {}
  public record View(UUID id,String status,Outcome result,String errorCode,String question) {}
  public record HistoryItem(UUID id,java.time.Instant createdAt,String status,String errorCode,String strategy,boolean automatic) {}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public AgentHistory.Page<HistoryItem> triageHistory(ActorContext actor,UUID project,UUID task,String cursor,int limit) {
    access.read(actor,project);access.maintainer(actor,project);
    var after=AgentHistory.parse(cursor);AgentHistory.limit(limit);
    var args=new ArrayList<Object>(List.of(bytes(project),bytes(task)));
    String condition="";
    if(after!=null){condition=" AND (created_at<? OR (created_at=? AND id<?))";var time=Utc.value(after.createdAt());args.addAll(List.of(time,time,bytes(after.id())));}
    args.add(limit+1);
    var rows=db.query("SELECT id,created_at,status,error_code,triage_strategy,automatic_task_id FROM agent_run WHERE project_id=? AND triage_task_id=?"+condition+" ORDER BY created_at DESC,id DESC LIMIT ?",
        (r,n)->new HistoryItem(read(r,"id"),Utc.read(r,"created_at"),r.getString("status"),r.getString("error_code"),r.getString("triage_strategy"),r.getBytes("automatic_task_id")!=null),args.toArray());
    boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
    var last=items.isEmpty()?null:items.get(items.size()-1);
    return new AgentHistory.Page<>(items,more?new AgentHistory.Cursor(last.createdAt(),last.id()).encode():null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View create(ActorContext actor,UUID project,Request request,String key) {
    return createRun(actor,project,request,key,null,null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View createTriage(ActorContext actor,UUID project,UUID task,Request request,String key) {
    access.lock(actor,project);access.maintainer(actor,project);
    return createRun(actor,project,request,key,task,null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View createTriage(ActorContext actor,UUID project,UUID task,TriageRequest request,String key) {
    access.lock(actor,project);access.maintainer(actor,project);
    return createRun(actor,project,new Request(request.question()),key,task,request.strategy()==null?TriageStrategy.AGENT:request.strategy());
  }
  /** Called only by the synchronous local task-created listener, within its transaction. */
  @Transactional(propagation=Propagation.MANDATORY)
  public void createAutomatic(ActorContext actor,dev.workhub.work.Task task,TriageStrategy strategy) {
    var project=access.lock(actor,task.projectId());
    if(!task.createdBy().equals(actor.userId()) || task.origin()!=dev.workhub.work.TaskOrigin.MANUAL
        || task.status()!=dev.workhub.work.TaskStatus.TRIAGE || !task.triageRequired())throw ApiException.forbidden();
    registerAutomatic(actor.userId(),task,strategy,project.businessRevision(),Map.of("source","LOCAL_SUBMISSION"));
  }
  /** Internal import path: authorization belongs to the project, not the importer/enabler's membership. */
  @Transactional(propagation=Propagation.MANDATORY)
  public void createImportedAutomatic(UUID grantedBy,dev.workhub.work.Task task,TriageStrategy strategy,
      dev.workhub.integration.github.GitHubIssueImported event) {
    var actor=ActorContext.projectAnalysis(grantedBy,task.projectId(),task.id());
    var project=access.read(actor,task.projectId());
    if(task.origin()!=dev.workhub.work.TaskOrigin.GITHUB)throw ApiException.forbidden();
    registerAutomatic(grantedBy,task,strategy,project.businessRevision(),Map.of("source","GITHUB_IMPORT",
        "resourceId",event.resource(),"causeJobId",event.causeJob(),"sourceCreatedAt",event.sourceCreatedAt(),"discoveryMode",event.discoveryMode()));
  }
  private void registerAutomatic(UUID requestedBy,dev.workhub.work.Task task,TriageStrategy strategy,long revision,Map<String,Object> trigger) {
    if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_run WHERE automatic_task_id=?)",Boolean.class,bytes(task.id())))return;
    UUID id=UUID.randomUUID();String error=null;
    try{teams.available(task.projectId());}catch(ApiException e){error=e.code;}
    db.update("INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision,triage_task_id,triage_task_revision,triage_discussion_revision,triage_strategy,automatic_task_id,status,error_code,completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        bytes(id),bytes(task.projectId()),bytes(requestedBy),"根据这项待分诊任务的描述、评论和有关资料，整理接纳建议，解释类型和优先级依据，比较相关任务；资料不足时明确说明。",
        json.write(contract(strategy)),revision,bytes(task.id()),task.revision(),task.discussionRevision(),strategy.name(),bytes(task.id()),
        error==null?"QUEUED":"FAILED",error,error==null?null:Utc.value(java.time.Instant.now()));
    if(error==null)teams.bind(id,task.projectId(),Objects.toString(trigger.get("source")));
    var provenance=new TreeMap<String,Object>(trigger);provenance.put("requestedBy",requestedBy);
    provenance.put("grantedBy",uuid(db.queryForObject("SELECT updated_by FROM project_triage_settings WHERE project_id=?",byte[].class,bytes(task.projectId()))));
    provenance.put("settingsRevision",db.queryForObject("SELECT revision FROM project_triage_settings WHERE project_id=?",Long.class,bytes(task.projectId())));
    db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,?,?)",bytes(id),bytes(UUID.randomUUID()),"AUTOMATIC_TRIGGER",json.write(provenance));
    // Admission is deferred to the worker: a busy project must not reject a valid task submission.
    if(error==null)jobs.enqueue("RUN_AGENT",task.projectId(),id,"agent/"+id,Map.of("runId",id));
  }
  private View createRun(ActorContext actor,UUID project,Request request,String key,UUID task,TriageStrategy strategy) {
    var p=access.lockManagement(actor,project);teams.available(project);
    String question=Inputs.text(request.question(),"question",2000);Inputs.text(key,"Idempotency-Key",128);
    var hash=Inputs.hash(json.write(List.of(task==null?"agent-readonly":"agent-triage/"+task+"/"+Objects.toString(strategy,"legacy"),project,question)));
    UUID old=receipts.find(actor.userId(),key,hash);if(old!=null)return get(actor,project,old);
    long active=db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=? AND status IN ('QUEUED','RUNNING')",Long.class,bytes(project));
    if(active>=3)throw new ApiException(429,"AGENT_BUSY","项目正在分析，请稍后再试");
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision) VALUES (?,?,?,?,?,?)",bytes(id),bytes(project),bytes(actor.userId()),question,json.write(task==null?contract(false):contract(strategy)),p.businessRevision());
    if(task!=null) {
      var rows=db.queryForList("SELECT revision,discussion_revision FROM task WHERE project_id=? AND id=? AND origin IN ('MANUAL','AGENT','GITHUB') AND status='TRIAGE' AND triage_required=true",bytes(project),bytes(task));
      if(rows.isEmpty())throw ApiException.invalid("仅支持已有待分诊任务");
      var t=rows.get(0);
      db.update("UPDATE agent_run SET triage_task_id=?,triage_task_revision=?,triage_discussion_revision=?,triage_strategy=? WHERE id=?",bytes(task),t.get("revision"),t.get("discussion_revision"),strategy==null?null:strategy.name(),bytes(id));
    }
    teams.bind(id,project,task==null?"MANUAL_QUESTION":"MANUAL_TRIAGE");
    jobs.enqueue("RUN_AGENT",project,id,"agent/"+id,Map.of("runId",id));receipts.save(actor.userId(),key,project,hash,id);
    return new View(id,"QUEUED",null,null,question);
  }
  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public View get(ActorContext actor,UUID project,UUID id) {
    var row=accessible(actor,project,id);
    Outcome result=row.get("result")==null?null:json.read((String)row.get("result"),Outcome.class);
    String status=(String)row.get("status"),error=(String)row.get("error_code");
    if(result!=null&&result.answer()!=null) {
      try{tools.validate(actor,project,((Number)row.get("business_revision")).longValue(),result.sources());}
      catch(ApiException e){if(e.status==403)throw e;status="STALE";error=e.code;result=null;}
    }
    return new View(id,status,result,error,(String)row.get("question"));
  }
  public Map<String,Object> accessible(ActorContext actor,UUID project,UUID id) {
    access.read(actor,project);
    var row=db.queryForList("SELECT * FROM agent_run WHERE id=? AND project_id=?",bytes(id),bytes(project)).stream().findFirst().orElseThrow(ApiException::missing);
    if(Boolean.TRUE.equals(row.get("content_deleted")))throw ApiException.missing();
    if(row.get("triage_task_id")!=null)access.maintainer(actor,project);
    else if(!Arrays.equals((byte[])row.get("actor_id"),bytes(actor.userId())))throw ApiException.missing();
    return row;
  }
}
