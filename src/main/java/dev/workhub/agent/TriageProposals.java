package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import static dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** A single whitelisted local command. Model outputs never become Task PATCH bodies. */
@Service
public class TriageProposals {
  public record Fields(TaskType type,Integer priority,UUID assigneeId,LocalDate dueDate,String rationale) {}
  public record Content(String action,UUID taskId,Task before,long discussionRevision,Fields fields,
      Answer analysis,List<Citation> sources) {}
  public record Edit(int version,String hash,Fields fields) {}
  public record Approval(int version,String hash,UUID previewToken) {}
  public record View(UUID id,int version,String hash,String status,Content content,UUID previewToken,
      Task result,String errorCode) {}
  private final ProjectAccess access; private final TaskService tasks; private final AgentRuns runs;
  private final AgentTools tools; private final JdbcTemplate db; private final Json json;
  private final TransactionTemplate tx;
  public TriageProposals(ProjectAccess access,TaskService tasks,AgentRuns runs,AgentTools tools,
      JdbcTemplate db,Json json,PlatformTransactionManager manager) {
    this.access=access;this.tasks=tasks;this.runs=runs;this.tools=tools;this.db=db;this.json=json;
    tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View create(ActorContext actor,UUID project,UUID run) {
    access.lock(actor,project);access.maintainer(actor,project);
    var raw=runs.accessible(actor,project,run);
    var existing=db.queryForList("SELECT id FROM agent_proposal WHERE run_id=?",bytes(run));
    if(!existing.isEmpty())return view(actor,project,uuid((byte[])existing.get(0).get("id")),null);
    if(raw.get("triage_task_id")==null)throw ApiException.invalid("只读问答不能转为执行提案");
    var r=runs.get(actor,project,run);
    if(!r.status().equals("SUCCEEDED") || r.result()==null || r.result().answer().suggestion()==null)
      throw new ApiException(409,"PROPOSAL_ANALYSIS_REQUIRED","需要有效的分诊分析结果");
    UUID task=uuid((byte[])raw.get("triage_task_id"));var before=tasks.get(actor,project,task);
    if(before.revision()!=((Number)raw.get("triage_task_revision")).longValue()
        || before.discussionRevision()!=((Number)raw.get("triage_discussion_revision")).longValue())throw stale();
    var s=r.result().answer().suggestion();
    var fields=new Fields(s.type(),s.priority(),null,before.dueDate(),"请核对分析依据并选择负责人；资料不足的字段须由维护者补齐。");
    var c=new Content("ACCEPT_TRIAGE",task,before,before.discussionRevision(),fields,r.result().answer(),r.result().sources());
    validate(actor,project,c);
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO agent_proposal(id,project_id,run_id,task_id,latest_version) VALUES (?,?,?,?,1)",bytes(id),bytes(project),bytes(run),bytes(task));
    insert(actor,id,1,c);return view(actor,project,id,null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View get(ActorContext actor,UUID project,UUID id) {
    access.lockManagement(actor,project);access.maintainer(actor,project);return view(actor,project,id,null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public List<UUID> list(ActorContext actor,UUID project,UUID task) {
    // Legacy UI selects the last ID: keep the newest window in ascending creation order.
    var ids=new ArrayList<>(history(actor,project,task,null,30).items().stream().map(HistoryItem::id).toList());
    Collections.reverse(ids);return List.copyOf(ids);
  }
  public record HistoryItem(UUID id,java.time.Instant createdAt,int version,String strategy,boolean executed) {}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public AgentHistory.Page<HistoryItem> history(ActorContext actor,UUID project,UUID task,String cursor,int limit) {
    access.read(actor,project);access.maintainer(actor,project);
    var after=AgentHistory.parse(cursor);AgentHistory.limit(limit);
    var args=new ArrayList<Object>(List.of(bytes(project),bytes(task)));
    String condition="";
    if(after!=null){condition=" AND (v.created_at<? OR (v.created_at=? AND p.id<?))";var time=Utc.value(after.createdAt());args.addAll(List.of(time,time,bytes(after.id())));}
    args.add(limit+1);
    var rows=db.query("SELECT p.id,v.created_at,p.latest_version,r.triage_strategy,(e.proposal_id IS NOT NULL) executed FROM agent_proposal p"
        +" JOIN agent_proposal_version v ON v.proposal_id=p.id AND v.version=1 JOIN agent_run r ON r.id=p.run_id"
        +" LEFT JOIN agent_proposal_execution e ON e.proposal_id=p.id WHERE p.project_id=? AND p.task_id=?"+condition+" ORDER BY v.created_at DESC,p.id DESC LIMIT ?",
        (r,n)->new HistoryItem(read(r,"id"),Utc.read(r,"created_at"),r.getInt("latest_version"),r.getString("triage_strategy"),r.getBoolean("executed")),args.toArray());
    boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
    var last=items.isEmpty()?null:items.get(items.size()-1);
    return new AgentHistory.Page<>(items,more?new AgentHistory.Cursor(last.createdAt(),last.id()).encode():null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View edit(ActorContext actor,UUID project,UUID id,Edit request) {
    access.lock(actor,project);access.maintainer(actor,project);
    var v=view(actor,project,id,null);match(v,request.version(),request.hash());
    if(v.result()!=null)throw ApiException.invalid("已执行的提案不能编辑");
    var old=v.content();var c=new Content(old.action(),old.taskId(),old.before(),old.discussionRevision(),request.fields(),old.analysis(),old.sources());
    validate(actor,project,c);int version=v.version()+1;insert(actor,id,version,c);
    db.update("UPDATE agent_proposal SET latest_version=?,last_error=NULL WHERE id=?",version,bytes(id));
    return view(actor,project,id,null);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View preview(ActorContext actor,UUID project,UUID id,Edit binding) {
    access.lock(actor,project);access.maintainer(actor,project);
    if(binding.fields()!=null)throw ApiException.invalid("预览不接受编辑字段，请先保存新版本");
    var v=view(actor,project,id,null);match(v,binding.version(),binding.hash());
    if(v.result()!=null)return v;
    validate(actor,project,v.content());UUID token=UUID.randomUUID();
    db.update("INSERT INTO agent_proposal_preview(token,proposal_id,version,actor_id,content_hash) VALUES (?,?,?,?,?)",bytes(token),bytes(id),v.version(),bytes(actor.userId()),v.hash());
    return view(actor,project,id,token);
  }
  public View approve(ActorContext actor,UUID project,UUID id,Approval request) {
    try { return tx.execute(status->execute(actor,project,id,request)); }
    catch(RuntimeException e) {
      // Separate post-rollback diagnostic; cannot turn a concurrent success into failure.
      try { tx.executeWithoutResult(status->{access.lockManagement(actor,project);access.maintainer(actor,project);
        db.update("UPDATE agent_proposal SET last_error=? WHERE id=? AND project_id=? AND latest_version=? AND NOT EXISTS (SELECT 1 FROM agent_proposal_execution WHERE proposal_id=?)",
            e instanceof ApiException a?a.code:"PROPOSAL_EXECUTION_FAILED",bytes(id),bytes(project),request.version(),bytes(id));});
      } catch(RuntimeException ignored) { /* Revoked callers cannot write or read diagnostics. */ }
      throw e;
    }
  }
  private View execute(ActorContext actor,UUID project,UUID id,Approval request) {
    access.lockManagement(actor,project);access.maintainer(actor,project);
    var v=view(actor,project,id,null);match(v,request.version(),request.hash());
    if(request.previewToken()==null || db.queryForObject("SELECT COUNT(*) FROM agent_proposal_preview WHERE token=? AND proposal_id=? AND version=? AND content_hash=? AND actor_id=?",Integer.class,
        bytes(request.previewToken()),bytes(id),v.version(),v.hash(),bytes(actor.userId()))!=1)
      throw new ApiException(409,"PROPOSAL_PREVIEW_REQUIRED","请先预览此版本，再明确批准");
    // Auth checked even on retries. A committed receipt takes precedence over subsequent source/archive changes.
    if(v.result()!=null)return v;
    access.lock(actor,project);validate(actor,project,v.content());var f=v.content().fields();
    if(f.assigneeId()==null || f.type()==null || f.priority()==null)throw ApiException.invalid("接纳前请补齐类型、优先级及有效负责人，并重新预览");
    var patch=new TaskRequests.Patch();patch.setType(f.type());patch.setPriority(f.priority());patch.setAssigneeId(f.assigneeId());patch.setDueDate(f.dueDate());
    var result=tasks.acceptTriage(new ActorContext(actor.userId(),"APPROVED_AGENT"),project,v.content().taskId(),v.content().before().revision(),patch);
    db.update("INSERT INTO agent_proposal_execution(proposal_id,version,content_hash,approved_by,preview_token,result) VALUES (?,?,?,?,?,?)",
        bytes(id),v.version(),v.hash(),bytes(actor.userId()),bytes(request.previewToken()),json.write(result));
    db.update("UPDATE agent_proposal SET last_error=NULL WHERE id=?",bytes(id));
    return view(actor,project,id,null);
  }
  private View view(ActorContext actor,UUID project,UUID id,UUID token) {
    var rows=db.queryForList("SELECT v.version,v.content,v.content_hash,p.last_error,e.result FROM agent_proposal p JOIN agent_proposal_version v ON v.proposal_id=p.id AND v.version=p.latest_version LEFT JOIN agent_proposal_execution e ON e.proposal_id=p.id WHERE p.id=? AND p.project_id=?",bytes(id),bytes(project));
    if(rows.isEmpty())throw ApiException.missing();var r=rows.get(0);
    var c=json.read((String)r.get("content"),Content.class);var result=r.get("result")==null?null:json.read((String)r.get("result"),Task.class);
    String state=result==null?"UNAPPROVED":"SUCCEEDED",error=(String)r.get("last_error");
    if(result==null)try { if(access.read(actor,project).archived())throw new ApiException(409,"PROJECT_ARCHIVED","项目已归档");validate(actor,project,c);if(error!=null)state="FAILED"; }
    catch(ApiException e){if(e.status==403)throw e;state="STALE";error=e.code;}
    return new View(id,((Number)r.get("version")).intValue(),(String)r.get("content_hash"),state,c,token,result,error);
  }
  private void validate(ActorContext actor,UUID project,Content c) {
    if(!"ACCEPT_TRIAGE".equals(c.action()) || !c.taskId().equals(c.before().id()))throw ApiException.invalid("不支持的提案动作");
    var t=tasks.get(actor,project,c.taskId());
    if(t.status()!=TaskStatus.TRIAGE || !t.triageRequired()
        || t.revision()!=c.before().revision() || t.discussionRevision()!=c.discussionRevision())throw stale();
    var f=c.fields();if(f==null || f.priority()!=null && (f.priority()<0 || f.priority()>3))throw ApiException.invalid("优先级须为0至3");
    Inputs.text(f.rationale(),"rationale",4000);access.assignee(project,f.assigneeId());
    tools.validate(actor,project,0,c.sources());
  }
  private void insert(ActorContext actor,UUID id,int version,Content c) {
    String body=json.write(c);db.update("INSERT INTO agent_proposal_version(proposal_id,version,content,content_hash,created_by) VALUES (?,?,?,?,?)",bytes(id),version,body,Inputs.hash(body),bytes(actor.userId()));
  }
  private void match(View v,int version,String hash) {
    if(v.version()!=version || !v.hash().equals(hash))throw new ApiException(409,"PROPOSAL_VERSION_CONFLICT","提案已修改，请重新预览当前版本");
  }
  private ApiException stale(){return new ApiException(409,"PROPOSAL_STALE","任务或讨论已变化，请重新分析");}
}
