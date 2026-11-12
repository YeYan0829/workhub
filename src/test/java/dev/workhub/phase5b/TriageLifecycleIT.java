package dev.workhub.phase5b;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.agent.TriageProposals.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ai.chat.messages.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "workhub.messaging.enabled=false","workhub.retrieval.enabled=false","workhub.embedding.enabled=false",
  "workhub.agent.legacy-mode=true","workhub.agent.enabled=true","workhub.github.enabled=false","workhub.bootstrap.login=",
  "workhub.agent.endpoint=http://127.0.0.1:9/api/paas/v4/chat/completions","workhub.agent.api-key=synthetic-no-network",
  "workhub.agent.model=glm-4.7","workhub.agent.paid-approved=false","workhub.agent.budget-usd=0",
  "server.servlet.session.cookie.secure=false","debug=false","logging.level.root=INFO","logging.level.org.springframework.jdbc=INFO"})
@AutoConfigureMockMvc
@Import(TriageLifecycleIT.ModelConfiguration.class)
class TriageLifecycleIT {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","13306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("workhub.agent.authorization-id",()->"p5b-free-"+UUID.randomUUID());
  }
  @TestConfiguration static class ModelConfiguration {
    @Bean @Primary ScriptModel scriptModel(){return new ScriptModel();}
  }
  static class ScriptModel implements AgentModel {
    UUID task,document; boolean insufficient;int turn;Runnable beforeAnswer; java.util.function.IntConsumer onCall;
    public Reply call(List<Message> messages,List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> tools,long millis) {
      assertThat(tools).allSatisfy(t->assertThat(t.getFunction().getName()).doesNotContain("accept","approve","sql","write"));
      int i=turn++;if(onCall!=null)onCall.accept(i);if(tools.isEmpty())i=3;String name=null,args=null;
      if(i==0){name="get_task";args="{\"taskId\":\""+task+"\"}";}
      if(i==1){name="list_comments";args="{\"taskId\":\""+task+"\"}";}
      if(i==2){name="read_content";args="{\"objectType\":\"DOCUMENT\",\"objectId\":\""+document+"\",\"length\":4000}";}
      if(name!=null)return new Reply(AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall("c"+i,"function",name,args))).build(),100,40,"tool_calls","glm-4.7");
      if(beforeAnswer!=null){var action=beforeAnswer;beforeAnswer=null;action.run();}
      boolean triage=messages.stream().anyMatch(m->Boolean.TRUE.equals(m.getMetadata().get("workhubTriage")));
      var refs=new ArrayList<String>();for(var m:messages)if(m.getMetadata().get("workhubSourceReferences") instanceof Collection<?> ids)for(var id:ids)refs.add("\""+id+"\"");
      String body="{\"paragraphs\":[{\"text\":\""+(insufficient?"资料不足，类型和优先级需补充；负责人由维护者选择。":"合成建议：取消后应恢复名额，复测不符合约定，建议 BUG / P1；根因未知，负责人由维护者选择。")+"\",\"citations\":["+String.join(",",refs)+"]}]"+(triage?",\"suggestion\":{\"type\":"+(insufficient?"null":"\"BUG\"")+",\"priority\":"+(insufficient?"null":"1")+"}":"")+"}";
      if(triage)body=body.substring(0,body.length()-1)+",\"investigation\":{\"recommendation\":\""+(insufficient?"NEED_INFO":"ACCEPT")+"\",\"relatedTasks\":[],\"questions\":[\"请补充影响范围\"]}}";
      return new Reply(new AssistantMessage(body),100,80,"stop","glm-4.7");
    }
  }
  @Autowired JdbcTemplate db;@Autowired Json json;@Autowired ObjectMapper mapper;
  @Autowired ProjectService projects;@Autowired TaskService tasks;@Autowired ContentService content;
  @Autowired AgentRuns runs;@Autowired TriageProposals proposals;@Autowired WorkJobExecutor executor;
  @Autowired OutboxRepository outbox;@Autowired ScriptModel model;@Autowired MockMvc http;
  @Autowired PasswordEncoder passwords;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  ActorContext actor,member;UUID project,workspace,document;Task task;String login;
  @BeforeEach void setup()throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_test");
    actor=new ActorContext(UUID.randomUUID());member=new ActorContext(UUID.randomUUID());workspace=UUID.randomUUID();login="p5b-"+actor.userId();
    var f=mapper.readTree(Files.readString(Path.of("src/test/resources/phase5b/triage.json")));
    for(var a:List.of(actor,member))db.update("INSERT INTO app_user(id,login,display_name,password_hash,email_verified) VALUES (?,?,?,?,true)",bytes(a.userId()),a==actor?login:"p5b-"+a.userId(),f.path("roles").path(a==actor?"maintainer":"member").path("name").asText(),passwords.encode("Synthetic5b!"));
    db.update("INSERT INTO workspace(id,name) VALUES (?,?)",bytes(workspace),"Phase 5B synthetic fixture");
    for(var a:List.of(actor,member))db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(a.userId()));
    project=projects.create(actor,workspace,f.path("project").asText(),key()).id();
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,true,revision());
    task=tasks.create(member,project,new TaskRequests.Create(f.at("/task/title").asText(),f.at("/task/description").asText(),null,null,null,null,null),key());
    content.createComment(member,project,task.id(),f.path("comment").asText(),key());
    document=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,f.at("/document/title").asText(),f.at("/document/body").asText(),key()).id();
    task=tasks.get(actor,project,task.id());model.task=task.id();model.document=document;model.turn=0;model.insufficient=false;model.beforeAnswer=null;model.onCall=null;
  }
  @AfterEach void cleanup(){
    db.update("UPDATE project_member SET active=false WHERE project_id=?",bytes(project));
    db.update("UPDATE workspace_member SET active=false WHERE workspace_id=?",bytes(workspace));
    for(var a:List.of(actor,member))db.update("UPDATE app_user SET active=false WHERE id=?",bytes(a.userId()));
  }
  String key(){return UUID.randomUUID().toString();}long revision(){return projects.get(actor,project).businessRevision();}
  void pump(){
    var rows=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler='RUN_AGENT' AND j.status='WAITING'",bytes(project));
    for(var row:rows){var e=outbox.find(uuid((byte[])row.get("id")),false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  }
  UUID analyze(){model.turn=0;var r=runs.createTriage(actor,project,task.id(),new AgentRuns.Request("分析此待分诊任务"),key());pump();var end=runs.get(actor,project,r.id());assertThat(end.status()).as(end.errorCode()).isEqualTo("SUCCEEDED");return r.id();}
  View draft(){return proposals.create(actor,project,analyze());}
  Fields fields(){return new Fields(TaskType.BUG,1,member.userId(),java.time.LocalDate.of(2026,11,1),"维护者确认按复测及取消约定处理，并选择陈成员执行。");}
  View ready(){var v=draft();v=proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),fields()));return preview(v);}
  View preview(View v){return proposals.preview(actor,project,v.id(),new Edit(v.version(),v.hash(),null));}
  Approval approval(View v){return new Approval(v.version(),v.hash(),v.previewToken());}
  String snapshot(){var result=new ArrayList<Object>();for(String table:List.of("task","comment","project_document","activity","notification","task_subscription","knowledge_source"))result.add(db.queryForList("SELECT * FROM "+table+" WHERE project_id=? ORDER BY 1",bytes(project)));result.add(db.queryForList("SELECT * FROM operation_receipt WHERE project_id=? AND result_id NOT IN (SELECT id FROM agent_run WHERE project_id=?) ORDER BY actor_id,request_key",bytes(project),bytes(project)));
    result.add(db.queryForList("SELECT * FROM work_job WHERE project_id=? AND handler<>'RUN_AGENT' ORDER BY id",bytes(project)));
    result.add(db.queryForList("SELECT o.* FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler<>'RUN_AGENT' ORDER BY o.id",bytes(project)));
    result.add(revision());return json.write(result);}
  void rejected(Runnable call,String code){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo(code));}

  @Autowired CollaborationService collaboration;
  @Autowired ProjectAccess access;
  @Autowired AgentAudit audit;
  void successor(){projects.member(actor,project,member.userId(),ProjectRole.MAINTAINER,true,revision());}
  void handoff(boolean remove){projects.member(member,project,actor.userId(),ProjectRole.MEMBER,!remove,projects.get(member,project).businessRevision());}
  UUID queued(TriageStrategy strategy){return runs.createTriage(actor,project,task.id(),new AgentRuns.TriageRequest("项目分诊",strategy),key()).id();}
  void archive(){collaboration.project(actor,project,"归档测试","",true,revision());}
  void withdraw(){var t=tasks.get(member,project,task.id());var patch=new TaskRequests.Patch();patch.setStatus(TaskStatus.CANCELED);tasks.update(member,project,t.id(),t.revision(),patch);}
  long calls(UUID run){return db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Long.class,bytes(run));}

  @Test void queuedAnalysisSurvivesRemovalAndSuccessorCanApproveWithoutAnotherAnalysis()throws Exception {
    successor();UUID run=queued(TriageStrategy.AGENT);handoff(true);
    // Losing the original account/workspace membership must not revoke project work.
    db.update("UPDATE workspace_member SET active=false WHERE workspace_id=? AND user_id=?",bytes(workspace),bytes(actor.userId()));
    db.update("UPDATE app_user SET active=false WHERE id=?",bytes(actor.userId()));
    pump();assertThat(model.turn).isEqualTo(4);assertThat(runs.get(member,project,run).status()).isEqualTo("SUCCEEDED");
    assertThat(runs.triageHistory(member,project,task.id(),null,30).items()).extracting(AgentRuns.HistoryItem::id).containsExactly(run);
    assertThat(uuid(db.queryForObject("SELECT actor_id FROM agent_run WHERE id=?",byte[].class,bytes(run)))).isEqualTo(actor.userId());
    rejected(()->runs.get(actor,project,run),"FORBIDDEN");rejected(()->proposals.create(actor,project,run),"FORBIDDEN");
    var v=proposals.create(member,project,run);v=proposals.edit(member,project,v.id(),new Edit(v.version(),v.hash(),fields()));
    v=proposals.preview(member,project,v.id(),new Edit(v.version(),v.hash(),null));
    assertThat(proposals.approve(member,project,v.id(),approval(v)).result().status()).isEqualTo(TaskStatus.TODO);
    assertThat(calls(run)).isEqualTo(4);assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal WHERE run_id=?",Integer.class,bytes(run))).isEqualTo(1);
    assertThat(uuid(db.queryForObject("SELECT approved_by FROM agent_proposal_execution WHERE proposal_id=?",byte[].class,bytes(v.id())))).isEqualTo(member.userId());
  }
  @Test void runningAnalysisSurvivesHandoffBetweenModelCalls() {
    successor();UUID run=queued(TriageStrategy.AGENT);model.onCall=i->{if(i==1)handoff(true);};
    pump();assertThat(runs.get(member,project,run).status()).isEqualTo("SUCCEEDED");assertThat(calls(run)).isEqualTo(4);
    assertThat(proposals.create(member,project,run).status()).isEqualTo("UNAPPROVED");
  }
  @Test void completedAnalysisCanBeReusedAndFormerMaintainerCannotWrite()throws Exception {
    UUID run=analyze();successor();handoff(false);
    String base="/projects/"+project+"/agent";
    http.perform(get(base+"/triage/"+task.id()+"/runs").with(user("p5b-"+member.userId()))).andExpect(status().isOk());
    http.perform(get(base+"/runs/"+run).with(user("p5b-"+member.userId()))).andExpect(status().isOk());
    http.perform(post(base+"/runs/"+run+"/proposal").with(user(login)).with(csrf())).andExpect(status().isForbidden());
    var v=proposals.create(member,project,run);assertThat(proposals.create(member,project,run).id()).isEqualTo(v.id());
    http.perform(post(base+"/proposals/"+v.id()+"/versions").with(user(login)).with(csrf()).contentType("application/json")
        .content(json.write(new Edit(v.version(),v.hash(),fields())))).andExpect(status().isForbidden());
    assertThat(tasks.get(member,project,task.id()).status()).isEqualTo(TaskStatus.TRIAGE);assertThat(calls(run)).isEqualTo(4);
  }
  @Test void sharedTriageDoesNotExposePrivateQuestionsOrOtherProjects()throws Exception {
    var own=runs.create(actor,project,new AgentRuns.Request("个人问题"),key());successor();
    rejected(()->runs.get(member,project,own.id()),"NOT_FOUND");
    rejected(()->proposals.create(member,project,own.id()),"NOT_FOUND");
    UUID triage=queued(TriageStrategy.AGENT);UUID other=projects.create(member,workspace,"另一个项目",key()).id();
    rejected(()->runs.get(member,other,triage),"NOT_FOUND");
    assertThat(runs.triageHistory(member,other,task.id(),null,30).items()).isEmpty();
    assertThat(runs.triageHistory(member,project,task.id(),null,30).items()).extracting(AgentRuns.HistoryItem::id).containsExactly(triage);
    http.perform(get("/projects/"+project+"/agent/triage/"+task.id()+"/runs").param("cursor","broken").with(user(login))).andExpect(status().isUnprocessableEntity());
  }
  @Test void successorMustPreviewExistingProposalWithTheirOwnIdentity() {
    var old=ready();successor();handoff(false);
    UUID run=uuid(db.queryForObject("SELECT run_id FROM agent_proposal WHERE id=?",byte[].class,bytes(old.id())));
    assertThat(proposals.create(member,project,run).id()).isEqualTo(old.id());
    rejected(()->proposals.approve(member,project,old.id(),approval(old)),"PROPOSAL_PREVIEW_REQUIRED");
    rejected(()->proposals.approve(actor,project,old.id(),approval(old)),"FORBIDDEN");
    var fresh=proposals.preview(member,project,old.id(),new Edit(old.version(),old.hash(),null));
    assertThat(proposals.approve(member,project,fresh.id(),approval(fresh)).result().status()).isEqualTo(TaskStatus.TODO);
    assertThat(calls(run)).isEqualTo(4);
  }
  @Test void projectReadCapabilityCannotCrossProjectOrPerformBusinessWrites() {
    var scoped=ActorContext.projectAnalysis(actor.userId(),project,task.id());
    UUID other=projects.create(actor,workspace,"其他",key()).id();
    assertThat(tasks.get(scoped,project,task.id()).id()).isEqualTo(task.id());
    rejected(()->access.read(scoped,other),"FORBIDDEN");rejected(()->access.maintainer(scoped,project),"FORBIDDEN");
    rejected(()->content.createComment(scoped,project,task.id(),"不允许模型评论",key()),"FORBIDDEN");
    rejected(()->tasks.create(scoped,project,new TaskRequests.Create("不允许模型建任务","",null,null,null,null,null),key()),"FORBIDDEN");
    rejected(()->collaboration.project(scoped,project,"不允许归档","",true,revision()),"FORBIDDEN");
    rejected(()->projects.create(scoped,workspace,"不允许借用原用户建项目",key()),"FORBIDDEN");
    rejected(()->projects.list(scoped,workspace),"FORBIDDEN");
    assertThat(access.canEditTask(scoped,task)).isFalse();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(TriageStrategy.class)
  void archivedQueueNeverCallsModel(TriageStrategy strategy) {
    UUID run=queued(strategy);archive();pump();pump();
    var r=runs.get(actor,project,run);assertThat(r.status()).isEqualTo("STALE");assertThat(r.errorCode()).isEqualTo("PROJECT_ARCHIVED");
    assertThat(calls(run)).isZero();assertThat(model.turn).isZero();
    assertThat(db.queryForObject("SELECT status FROM work_job WHERE owner_ref=? AND handler='RUN_AGENT'",String.class,bytes(run))).isEqualTo("DONE");
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(TriageStrategy.class)
  void archiveDuringModelStopsFurtherCallsButPreservesSettledUsage(TriageStrategy strategy) {
    UUID run=queued(strategy);model.onCall=i->{if(i==0)archive();};pump();
    assertThat(runs.get(actor,project,run).errorCode()).isEqualTo("PROJECT_ARCHIVED");assertThat(calls(run)).isEqualTo(1);assertThat(model.turn).isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(run))).isEqualTo("COMPLETE");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal WHERE run_id=?",Integer.class,bytes(run))).isZero();
  }
  @Test void withdrawnQueueStopsWithoutCallingModel() {
    UUID run=queued(TriageStrategy.AGENT);withdraw();pump();
    assertThat(runs.get(actor,project,run).errorCode()).isEqualTo("TRIAGE_NO_LONGER_REQUIRED");assertThat(calls(run)).isZero();
  }
  @Test void archiveDoesNotEraseUnknownChargeFromAnAlreadyAdmittedCall() {
    UUID run=queued(TriageStrategy.AGENT);model.onCall=i->{archive();throw new ApiException(503,"TRANSPORT_UNKNOWN","合成传输失败");};
    pump();assertThat(runs.get(actor,project,run).errorCode()).isEqualTo("PROJECT_ARCHIVED");
    assertThat(calls(run)).isEqualTo(1);assertThat(model.turn).isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(run))).isEqualTo("UNKNOWN");
  }
  @Test void withdrawnDuringModelDoesNotContinueOrPublishResult() {
    UUID run=queued(TriageStrategy.AGENT);model.onCall=i->{if(i==0)withdraw();};pump();
    var r=runs.get(actor,project,run);assertThat(r.errorCode()).isEqualTo("TRIAGE_NO_LONGER_REQUIRED");assertThat(r.result()).isNull();assertThat(calls(run)).isEqualTo(1);
  }
  @Test void callReservationAlsoRejectsArchiveAndNoLongerTriageBeforeCharging() {
    UUID run=queued(TriageStrategy.AGENT),attempt=UUID.randomUUID();
    db.update("UPDATE agent_run SET status='RUNNING',attempt_token=? WHERE id=?",bytes(attempt),bytes(run));
    archive();rejected(()->audit.reserve(run,attempt,100,100),"PROJECT_ARCHIVED");assertThat(calls(run)).isZero();
    collaboration.project(actor,project,"恢复","",false,revision());withdraw();
    rejected(()->audit.reserve(run,attempt,100,100),"TRIAGE_NO_LONGER_REQUIRED");assertThat(calls(run)).isZero();
  }
  @Test void historyUsesCreationTimeHasStablePaginationAndLegacyListKeepsLatest()throws Exception {
    // A real analyzed result seeds 32 real proposals without repeating identical model calls.
    UUID seed=analyze();String result=db.queryForObject("SELECT result FROM agent_run WHERE id=?",String.class,bytes(seed));
    var ids=new ArrayList<UUID>();var runIds=new ArrayList<UUID>();
    for(int i=0;i<32;i++) {
      UUID run=i==0?seed:queued(TriageStrategy.AGENT);
      if(i!=0)db.update("UPDATE agent_run SET status='SUCCEEDED',result=? WHERE id=?",result,bytes(run));
      UUID id=proposals.create(actor,project,run).id();ids.add(id);runIds.add(run);
      // Deliberately reverse creation-time order; last two share a timestamp to exercise the tie key.
      var time=Utc.value(java.time.Instant.parse("2026-10-15T00:00:00Z").plusSeconds(i<2?0:32-i));
      db.update("UPDATE agent_proposal_version SET created_at=? WHERE proposal_id=? AND version=1",time,bytes(id));
      db.update("UPDATE agent_run SET created_at=? WHERE id=?",time,bytes(run));
    }
    var expected=db.query("SELECT p.id FROM agent_proposal p JOIN agent_proposal_version v ON p.id=v.proposal_id AND v.version=1 WHERE p.project_id=? ORDER BY v.created_at DESC,p.id DESC",(r,n)->read(r,"id"),bytes(project));
    assertThat(expected.get(0)).isEqualTo(ids.get(2));
    var first=proposals.get(actor,project,ids.get(0));proposals.edit(actor,project,first.id(),new Edit(first.version(),first.hash(),fields()));
    var seen=new ArrayList<UUID>();String cursor=null;
    do{var page=proposals.history(actor,project,task.id(),cursor,7);seen.addAll(page.items().stream().map(TriageProposals.HistoryItem::id).toList());cursor=page.nextCursor();}while(cursor!=null);
    assertThat(seen).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    var latest=new ArrayList<>(expected.subList(0,30));Collections.reverse(latest);assertThat(proposals.list(actor,project,task.id())).containsExactlyElementsOf(latest);
    var runsSeen=new ArrayList<UUID>();cursor=null;
    do{var page=runs.triageHistory(actor,project,task.id(),cursor,7);runsSeen.addAll(page.items().stream().map(AgentRuns.HistoryItem::id).toList());cursor=page.nextCursor();}while(cursor!=null);
    assertThat(runsSeen).hasSize(32).containsExactlyInAnyOrderElementsOf(runIds).doesNotHaveDuplicates();
    rejected(()->proposals.history(member,project,task.id(),null,10),"FORBIDDEN");
    rejected(()->proposals.history(actor,project,task.id(),null,0),"VALIDATION_ERROR");
    http.perform(get("/projects/"+project+"/agent/triage/"+task.id()+"/proposals/history").param("limit","7").with(user(login)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(7)).andExpect(jsonPath("$.nextCursor").isString());
  }
}
