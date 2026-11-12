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
@Import(AutomaticTriageIT.ModelConfiguration.class)
class AutomaticTriageIT {
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

  @Autowired AutomaticTriage automatic;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired org.springframework.context.ApplicationEventPublisher events;
  void enable(){automatic.update(actor,project,new AutomaticTriage.Update(true,TriageStrategy.WORKFLOW,automatic.get(actor,project).revision()));}
  Task submit(){return tasks.create(member,project,new TaskRequests.Create("自动分诊合成任务","请核对取消后名额恢复",null,null,null,null,null),key());}
  UUID automaticRun(Task t){return uuid(db.queryForObject("SELECT id FROM agent_run WHERE automatic_task_id=?",byte[].class,bytes(t.id())));}
  void deliver(UUID id){var rows=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=?",bytes(id));for(var row:rows){var e=outbox.find(uuid((byte[])row.get("id")),false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
  long runCount(){return db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=?",Long.class,bytes(project));}
  org.springframework.transaction.support.TransactionTemplate tx(){var t=new org.springframework.transaction.support.TransactionTemplate(transactions);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}

  @Test void optInOnlyAffectsNewLocalTriageAndNoEditsOrHistoricalBackfill(){
    assertThat(automatic.get(actor,project).enabled()).isFalse();submit();assertThat(runCount()).isZero();
    enable();assertThat(runCount()).isZero();
    tasks.create(actor,project,new TaskRequests.Create("普通待办","",null,null,null,null,null),key());assertThat(runCount()).isZero();
    var t=submit();assertThat(runCount()).isEqualTo(1);
    var patch=new TaskRequests.Patch();patch.setDescription("补充说明");tasks.update(member,project,t.id(),t.revision(),patch);
    content.createComment(member,project,t.id(),"补充讨论",key());assertThat(runCount()).isEqualTo(1);
    assertThat(runs.triageHistory(actor,project,t.id(),null,10).items().get(0).automatic()).isTrue();
    assertThat(automatic.status(member,project,t.id()).status()).isEqualTo("QUEUED");
    rejected(()->runs.triageHistory(member,project,t.id(),null,10),"FORBIDDEN");
  }
  @Test void duplicateSubmissionAndDuplicateEventRegisterOneRunJobAndOutbox()throws Exception {
    enable();String k=key();var request=new TaskRequests.Create("重复提交","",null,null,null,null,null);
    var pool=Executors.newFixedThreadPool(2);
    try {
      var a=pool.submit(()->tasks.create(member,project,request,k));var b=pool.submit(()->tasks.create(member,project,request,k));
      var t=a.get(10,TimeUnit.SECONDS);assertThat(b.get(10,TimeUnit.SECONDS).id()).isEqualTo(t.id());
      tx().executeWithoutResult(x->events.publishEvent(new TaskService.Created(member,t)));
      assertThat(runCount()).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='RUN_AGENT'",Long.class,bytes(project))).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler='RUN_AGENT'",Long.class,bytes(project))).isEqualTo(1);
    }finally{pool.shutdownNow();}
  }
  @Test void rollbackRemovesTaskRunJobOutboxAndReceipt(){
    enable();String before=snapshot();
    tx().executeWithoutResult(x->{submit();assertThat(runCount()).isEqualTo(1);x.setRollbackOnly();});
    assertThat(runCount()).isZero();assertThat(snapshot()).isEqualTo(before);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='RUN_AGENT'",Long.class,bytes(project))).isZero();
  }
  @Test void busyProjectAcceptsNewTasksAndDefersWithoutStartingClockOrCallingModel(){
    enable();var all=new ArrayList<Task>();for(int i=0;i<4;i++)all.add(submit());assertThat(runCount()).isEqualTo(4);
    for(int i=0;i<3;i++)db.update("UPDATE agent_run SET status='RUNNING' WHERE id=?",bytes(automaticRun(all.get(i))));
    var waiting=automaticRun(all.get(3));model.task=all.get(3).id();deliver(waiting);
    assertThat(runs.get(actor,project,waiting).status()).isEqualTo("QUEUED");assertThat(model.turn).isZero();assertThat(calls(waiting)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='START'",Long.class,bytes(waiting))).isZero();
    db.update("UPDATE agent_run SET status='FAILED' WHERE project_id=? AND status='RUNNING'",bytes(project));
    db.update("UPDATE work_job SET next_attempt_at=UTC_TIMESTAMP(6) WHERE owner_ref=?",bytes(waiting));
    db.update("UPDATE outbox_message o JOIN work_job j ON j.id=o.job_id SET o.available_at=UTC_TIMESTAMP(6) WHERE j.owner_ref=?",bytes(waiting));
    deliver(waiting);assertThat(runs.get(actor,project,waiting).status()).isEqualTo("SUCCEEDED");
  }
  @Test void settingsRequireCurrentMaintainerCsrfAndFreshRevision()throws Exception {
    rejected(()->automatic.update(member,project,new AutomaticTriage.Update(true,TriageStrategy.WORKFLOW,0)),"FORBIDDEN");
    http.perform(put("/projects/"+project+"/agent/triage-settings").with(user(login)).contentType("application/json").content("{\"enabled\":true,\"strategy\":\"WORKFLOW\",\"expectedRevision\":0}")).andExpect(status().isForbidden());
    enable();rejected(()->automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.WORKFLOW,0)),"TRIAGE_SETTINGS_REVISION_CONFLICT");
    archive();rejected(()->automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.WORKFLOW,1)),"PROJECT_ARCHIVED");
  }
  @RepeatedTest(3) void disableCancelsQueuedAutomaticRunsWithoutCallingModel(){
    enable();var t=submit();model.task=t.id();var id=automaticRun(t);
    automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.WORKFLOW,1));submit();assertThat(runCount()).isEqualTo(1);
    pump();assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("AUTOMATIC_TRIAGE_DISABLED");assertThat(calls(id)).isZero();assertThat(model.turn).isZero();assertThat(tasks.get(actor,project,t.id()).status()).isEqualTo(TaskStatus.TRIAGE);
  }
  @RepeatedTest(3) void disableDuringCallSettlesThatCallButStopsNextCallEvenAfterReenable(){
    automatic.update(actor,project,new AutomaticTriage.Update(true,TriageStrategy.AGENT,0));
    var t=submit();model.task=t.id();var id=automaticRun(t);
    model.onCall=i->{if(i==0){
      automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.AGENT,1));
      automatic.update(actor,project,new AutomaticTriage.Update(true,TriageStrategy.AGENT,2));
    }};
    pump();
    assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("AUTOMATIC_TRIAGE_DISABLED");
    assertThat(model.turn).isEqualTo(1);assertThat(calls(id)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(id))).isEqualTo("COMPLETE");
    assertThat(tasks.get(actor,project,t.id()).status()).isEqualTo(TaskStatus.TRIAGE);
  }
  @Test void disablingAutomaticDoesNotCancelManualAnalysis(){
    enable();var id=queued(TriageStrategy.WORKFLOW);
    automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.WORKFLOW,1));
    pump();assertThat(runs.get(actor,project,id).status()).isEqualTo("SUCCEEDED");
  }
  @Test void queueExpiresBeforeBusyProjectAdmissionWithoutReservingOrCalling(){
    enable();var all=new ArrayList<Task>();for(int i=0;i<4;i++)all.add(submit());
    for(int i=0;i<3;i++)db.update("UPDATE agent_run SET status='RUNNING' WHERE id=?",bytes(automaticRun(all.get(i))));
    var id=automaticRun(all.get(3));
    db.update("UPDATE agent_run SET created_at=UTC_TIMESTAMP(6)-INTERVAL 601 SECOND WHERE id=?",bytes(id));
    deliver(id);assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("AGENT_QUEUE_TIMEOUT");
    assertThat(model.turn).isZero();assertThat(calls(id)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='START'",Long.class,bytes(id))).isZero();
  }
  @Test void submittingMemberCanLeaveAndSuccessorCanReuseAndApprove(){
    enable();var t=submit();model.task=t.id();var id=automaticRun(t);
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,false,revision());pump();
    assertThat(runs.get(actor,project,id).status()).isEqualTo("SUCCEEDED");
    var v=proposals.create(actor,project,id);
    v=proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),new Fields(TaskType.BUG,1,actor.userId(),null,"维护者确认")));
    v=proposals.preview(actor,project,v.id(),new Edit(v.version(),v.hash(),null));
    proposals.approve(actor,project,v.id(),new Approval(v.version(),v.hash(),v.previewToken()));
    assertThat(tasks.get(actor,project,t.id()).status()).isEqualTo(TaskStatus.TODO);assertThat(model.turn).isEqualTo(1);
  }
  @Test void archivedAutomaticRunDoesNotCallModel(){
    enable();var t=submit();var id=automaticRun(t);archive();pump();
    assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("PROJECT_ARCHIVED");assertThat(calls(id)).isZero();assertThat(model.turn).isZero();
  }
  @Test void withdrawnAutomaticRunDoesNotCallModel(){
    enable();task=submit();var id=automaticRun(task);withdraw();pump();
    assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("TRIAGE_NO_LONGER_REQUIRED");assertThat(calls(id)).isZero();assertThat(model.turn).isZero();
  }
  @Test void editDuringAnalysisCannotBeApprovedFromOldAutomaticSnapshot(){
    enable();var t=submit();model.task=t.id();var id=automaticRun(t);
    var patch=new TaskRequests.Patch();patch.setDescription("分析期间补充了描述");model.beforeAnswer=()->tasks.update(member,project,t.id(),t.revision(),patch);
    pump();assertThat(runs.get(actor,project,id).status()).isIn("STALE","SUCCEEDED");
    assertThatThrownBy(()->proposals.create(actor,project,id)).isInstanceOf(ApiException.class);
  }
  @Test void editsWhileQueuedAreIncludedInTheInitialAnalysisBasis(){
    enable();var t=submit();model.task=t.id();var id=automaticRun(t);
    var patch=new TaskRequests.Patch();patch.setDescription("排队期间补充资料");var updated=tasks.update(member,project,t.id(),t.revision(),patch);
    content.createComment(member,project,t.id(),"补充影响范围",key());
    pump();assertThat(runs.get(actor,project,id).status()).isEqualTo("SUCCEEDED");
    assertThat(proposals.create(actor,project,id).content().before().revision()).isEqualTo(updated.revision());
  }
  @Autowired AgentSettings settings;
  @Test void disabledModelRecordsFailureWithoutRejectingTaskSubmission(){
    enable();
    org.springframework.test.util.ReflectionTestUtils.setField(settings,"enabled",false);
    try {
      var t=submit();var id=automaticRun(t);
      assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("AGENT_DISABLED");
      assertThat(tasks.get(actor,project,t.id()).status()).isEqualTo(TaskStatus.TRIAGE);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE owner_ref=?",Long.class,bytes(id))).isZero();
    }finally{org.springframework.test.util.ReflectionTestUtils.setField(settings,"enabled",true);}
  }
  @Test void exhaustedBudgetLeavesTaskAndFailureRecordWithoutCallingModel(){
    enable();var t=submit();model.task=t.id();var id=automaticRun(t);
    String authorization=settings.authorization;long price=settings.inputPrice;
    // Nonzero synthetic token price exercises the actual reservation gate; there is no HTTP provider.
    org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization","auto-zero-budget-"+UUID.randomUUID());
    org.springframework.test.util.ReflectionTestUtils.setField(settings,"inputPrice",1L);
    try {
      pump();assertThat(runs.get(actor,project,id).errorCode()).isEqualTo("MODEL_COST_BUDGET");
      assertThat(calls(id)).isZero();assertThat(model.turn).isZero();
      assertThat(tasks.get(actor,project,t.id()).status()).isEqualTo(TaskStatus.TRIAGE);
    }finally{org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization",authorization);org.springframework.test.util.ReflectionTestUtils.setField(settings,"inputPrice",price);}
  }
  @Test void browserRecoversLostResponseIsolatesFailuresAndFindsSharedRuns()throws Exception {
    var process=new ProcessBuilder("node","scripts/verify-automatic-triage-ui.cjs",String.valueOf(port),login,project.toString(),task.id().toString(),member.userId().toString()).redirectErrorStream(true).redirectOutput(Path.of("target/automatic-triage-browser.log").toFile()).start();
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(240);
    while(process.isAlive() && System.nanoTime()<end){pump();Thread.sleep(150);}
    if(process.isAlive())process.destroyForcibly();
    assertThat(process.waitFor()).as(Files.readString(Path.of("target/automatic-triage-browser.log"))).isZero();
    assertThat(runCount()).isEqualTo(2);
    assertThat(automatic.get(actor,project).enabled()).isTrue();
  }
}
