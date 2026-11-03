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
  "workhub.agent.enabled=true","workhub.github.enabled=false","workhub.bootstrap.login=",
  "workhub.agent.endpoint=http://127.0.0.1:9/api/paas/v4/chat/completions","workhub.agent.api-key=synthetic-no-network",
  "workhub.agent.model=glm-4.7","workhub.agent.paid-approved=false","workhub.agent.budget-usd=0",
  "server.servlet.session.cookie.secure=false","debug=false","logging.level.root=INFO","logging.level.org.springframework.jdbc=INFO"})
@AutoConfigureMockMvc
@Import(TriageProposalIT.ModelConfiguration.class)
class TriageProposalIT {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","13306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("workhub.agent.authorization-id",()->"p5b-free-"+UUID.randomUUID());
  }
  @TestConfiguration static class ModelConfiguration {
    @Bean @Primary ScriptModel scriptModel(){return new ScriptModel();}
  }
  static class ScriptModel implements AgentModel {
    UUID task,document; boolean insufficient;int turn;Runnable beforeAnswer;
    public Reply call(List<Message> messages,List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> tools,long millis) {
      assertThat(tools).allSatisfy(t->assertThat(t.getFunction().getName()).doesNotContain("accept","approve","sql","write"));
      int i=turn++;if(tools.isEmpty())i=3;String name=null,args=null;
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
    task=tasks.get(actor,project,task.id());model.task=task.id();model.document=document;model.turn=0;model.insufficient=false;model.beforeAnswer=null;
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

  @Test void strategiesArePersistedAndCannotReuseAnotherModeIdempotencyKey(){
    String k=key();var request=new AgentRuns.TriageRequest("调查合成任务",TriageStrategy.GUIDED);
    var run=runs.createTriage(actor,project,task.id(),request,k);
    assertThat(runs.createTriage(actor,project,task.id(),request,k).id()).isEqualTo(run.id());
    assertThatThrownBy(()->runs.createTriage(actor,project,task.id(),new AgentRuns.TriageRequest("调查合成任务",TriageStrategy.AGENT),k)).isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT triage_strategy FROM agent_run WHERE id=?",String.class,bytes(run.id()))).isEqualTo("GUIDED");
  }
  @Test void fixedWorkflowPublishesInvestigationWithoutModelToolsAndCannotExecuteUnapproved(){
    String before=snapshot();var run=runs.createTriage(actor,project,task.id(),new AgentRuns.TriageRequest("调查合成任务",TriageStrategy.WORKFLOW),key());pump();
    var result=runs.get(actor,project,run.id());assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.result().rounds()).isEqualTo(1);assertThat(result.result().answer().investigation()).isNotNull();
    assertThat(snapshot()).isEqualTo(before);var v=proposals.create(actor,project,run.id());
    assertThat(v.status()).isEqualTo("UNAPPROVED");assertThat(snapshot()).isEqualTo(before);
  }
  @Test void fixedAndGuidedSourcesChangingDuringAnalysisStopAsStale(){
    for(var strategy:List.of(TriageStrategy.WORKFLOW,TriageStrategy.GUIDED)){
      model.turn=0;
      model.beforeAnswer=()->{
        var current=tasks.get(member,project,task.id());var patch=new TaskRequests.Patch();
        patch.setDescription("维护者分析时提交者补充了新复现条件："+strategy);
        tasks.update(member,project,task.id(),current.revision(),patch);
      };
      var run=runs.createTriage(actor,project,task.id(),new AgentRuns.TriageRequest("调查合成任务",strategy),key());pump();
      var result=runs.get(actor,project,run.id());
      assertThat(result.status()).as(strategy.name()).isEqualTo("STALE");
      assertThat(result.errorCode()).isEqualTo("AGENT_CONTEXT_CHANGED");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Integer.class,bytes(run.id())))
          .isEqualTo(strategy==TriageStrategy.WORKFLOW?1:4);
      rejected(()->proposals.create(actor,project,run.id()),"PROPOSAL_ANALYSIS_REQUIRED");
      assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.TRIAGE);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal WHERE run_id=?",Integer.class,bytes(run.id()))).isZero();
    }
  }
  @Test void analysisEditingAndPreviewNeverWriteBusinessAndExactAcceptIsDurable() {
    String before=snapshot();var v=draft();assertThat(snapshot()).isEqualTo(before);assertThat(v.content().fields().assigneeId()).isNull();
    assertThat(v.content().analysis().suggestion().type()).isEqualTo(TaskType.BUG);
    v=proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),fields()));v=preview(v);assertThat(snapshot()).isEqualTo(before);
    var result=proposals.approve(actor,project,v.id(),approval(v));var actual=tasks.get(actor,project,task.id());
    assertThat(actual).isEqualTo(result.result());assertThat(actual.status()).isEqualTo(TaskStatus.TODO);assertThat(actual.type()).isEqualTo(TaskType.BUG);
    assertThat(actual.priority()).isEqualTo(1);assertThat(actual.assigneeId()).isEqualTo(member.userId());assertThat(actual.dueDate()).isEqualTo(fields().dueDate());assertThat(actual.description()).isEqualTo(task.description());
    String after=snapshot();assertThat(proposals.approve(actor,project,v.id(),approval(v)).result()).isEqualTo(actual);assertThat(snapshot()).isEqualTo(after);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal_execution WHERE proposal_id=?",Integer.class,bytes(v.id()))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM activity WHERE project_id=? AND kind='TASK_ACCEPTED'",Integer.class,bytes(project))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification n JOIN activity a ON a.id=n.activity_id WHERE a.project_id=? AND a.kind='TASK_ACCEPTED'",Integer.class,bytes(project))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT actor_type FROM activity WHERE project_id=? AND kind='TASK_ACCEPTED'",String.class,bytes(project))).isEqualTo("APPROVED_AGENT");
    db.update("UPDATE project SET archived=true WHERE id=?",bytes(project));assertThat(proposals.approve(actor,project,v.id(),approval(v)).result()).isEqualTo(actual);
  }
  @Test void editsInvalidateApprovalAndHashAndRequireFreshPreview(){
    var old=ready();var newer=proposals.edit(actor,project,old.id(),new Edit(old.version(),old.hash(),new Fields(TaskType.QUESTION,3,member.userId(),null,"调整为资料核实，重新核对。")));
    String before=snapshot();rejected(()->proposals.approve(actor,project,old.id(),approval(old)),"PROPOSAL_VERSION_CONFLICT");
    rejected(()->proposals.approve(actor,project,newer.id(),new Approval(newer.version(),newer.hash(),old.previewToken())),"PROPOSAL_PREVIEW_REQUIRED");
    rejected(()->proposals.approve(actor,project,newer.id(),new Approval(newer.version(),"forged",old.previewToken())),"PROPOSAL_VERSION_CONFLICT");assertThat(snapshot()).isEqualTo(before);
    var fresh=preview(newer);assertThat(proposals.approve(actor,project,fresh.id(),approval(fresh)).result().priority()).isEqualTo(3);
  }
  @Test void insufficientInformationAndMissingOrInvalidOwnerCannotExecute()throws Exception {
    var input=mapper.readTree(Files.readString(Path.of("src/test/resources/phase5b/triage.json"))).path("insufficient");
    task=tasks.create(member,project,new TaskRequests.Create(input.path("title").asText(),input.path("description").asText(),null,null,null,null,null),key());model.task=task.id();
    model.insufficient=true;var v=preview(draft());assertThat(v.content().fields().type()).isNull();String before=snapshot();
    rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"VALIDATION_ERROR");
    rejected(()->proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),new Fields(TaskType.BUG,1,UUID.randomUUID(),null,"无效人员"))),"VALIDATION_ERROR");assertThat(snapshot()).isEqualTo(before);
  }
  @Test void taskAndNewDiscussionInvalidate(){
    var v=ready();content.createComment(member,project,task.id(),"合成冲突：影响范围改变，需重新分析。",key());String before=snapshot();
    rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"PROPOSAL_STALE");assertThat(snapshot()).isEqualTo(before);assertThat(proposals.get(actor,project,v.id()).status()).isEqualTo("STALE");
  }
  @Test void taskRevisionInvalidates(){var v=ready();var patch=new TaskRequests.Patch();patch.setDescription("已补充新的复现信息");tasks.update(member,project,task.id(),task.revision(),patch);String before=snapshot();rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"PROPOSAL_STALE");assertThat(snapshot()).isEqualTo(before);}
  @Test void citedDocumentChangeInvalidatesButUnrelatedChangesDoNot(){
    var v=ready();var d=content.getDocument(actor,project,document);content.editDocument(actor,project,document,d.revision(),d.title(),"原约定撤回，待重新确认。");String before=snapshot();
    rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"AGENT_CONTEXT_CHANGED");assertThat(snapshot()).isEqualTo(before);
  }
  @Test void unrelatedProjectCountersAndNotificationReadDoNotInvalidate(){
    var v=ready();content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"无关食堂文档","菜单",key());
    db.update("UPDATE notification SET read_at=UTC_TIMESTAMP(6) WHERE project_id=?",bytes(project));
    assertThat(proposals.approve(actor,project,v.id(),approval(v)).status()).isEqualTo("SUCCEEDED");
  }
  @Test void revokedMaintainerAndAssigneeAndArchiveAreRejected(){
    var v=ready();String before=snapshot();rejected(()->proposals.approve(member,project,v.id(),approval(v)),"FORBIDDEN");assertThat(snapshot()).isEqualTo(before);
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,false,revision());rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"VALIDATION_ERROR");
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,true,revision());db.update("UPDATE project SET archived=true WHERE id=?",bytes(project));rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"PROJECT_ARCHIVED");
    db.update("UPDATE project SET archived=false WHERE id=?",bytes(project));db.update("UPDATE project_member SET role='MEMBER' WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));rejected(()->proposals.approve(actor,project,v.id(),approval(v)),"FORBIDDEN");assertThat(tasks.get(member,project,task.id()).status()).isEqualTo(TaskStatus.TRIAGE);
  }
  @Test void concurrentApprovalsAndLostResponseReturnOneReceipt()throws Exception {
    var v=ready();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
    try {var calls=new ArrayList<Future<View>>();for(int i=0;i<2;i++)calls.add(pool.submit(()->{gate.await();return proposals.approve(actor,project,v.id(),approval(v));}));gate.countDown();
      assertThat(calls.get(0).get(20,TimeUnit.SECONDS).result()).isEqualTo(calls.get(1).get(20,TimeUnit.SECONDS).result());
    }finally{pool.shutdownNow();}String after=snapshot();assertThat(proposals.approve(actor,project,v.id(),approval(v)).status()).isEqualTo("SUCCEEDED");assertThat(snapshot()).isEqualTo(after);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal_execution WHERE proposal_id=?",Integer.class,bytes(v.id()))).isEqualTo(1);
  }
  @Test void receiptFailureRollsBackTaskActivityNotificationsAndObligations(){
    var v=ready();String before=snapshot();
    db.execute("ALTER TABLE agent_proposal_execution ADD CONSTRAINT p5b_receipt_failure CHECK (proposal_id <> X'"+v.id().toString().replace("-","")+"')");
    try {assertThatThrownBy(()->proposals.approve(actor,project,v.id(),approval(v))).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    finally{db.execute("ALTER TABLE agent_proposal_execution DROP CHECK p5b_receipt_failure");}
    assertThat(snapshot()).isEqualTo(before);assertThat(proposals.get(actor,project,v.id()).status()).isEqualTo("FAILED");
    assertThat(proposals.approve(actor,project,v.id(),approval(v)).status()).isEqualTo("SUCCEEDED");
  }
  @Test void readonlyRunAndHttpWhitelistCannotAcquireWriteCapability()throws Exception {
    String before=snapshot();var r=runs.create(actor,project,new AgentRuns.Request("说明任务"),key());pump();assertThat(runs.get(actor,project,r.id()).status()).isEqualTo("SUCCEEDED");
    rejected(()->proposals.create(actor,project,r.id()),"VALIDATION_ERROR");assertThat(snapshot()).isEqualTo(before);
    http.perform(post("/projects/"+project+"/agent/triage/"+task.id()+"/runs").with(user(login)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"question\":\"分析\",\"actorId\":\""+member.userId()+"\"}")).andExpect(status().isUnprocessableEntity());
    model.turn=0;var v=ready();http.perform(post("/projects/"+project+"/agent/proposals/"+v.id()+"/versions").with(user(login)).with(csrf()).contentType("application/json").content("{\"version\":2,\"hash\":\""+v.hash()+"\",\"fields\":{\"action\":\"SQL\"}}")).andExpect(status().isUnprocessableEntity());
  }
  @Test void httpApprovalsUseProposalIdentityAcrossDifferentKeysAndCheckScope()throws Exception {
    var v=ready();String body=json.write(approval(v));String url="/projects/"+project+"/agent/proposals/"+v.id()+"/approve";
    String first=http.perform(post(url).with(user(login)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String after=snapshot();String retry=http.perform(post(url).with(user(login)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(mapper.readTree(retry)).isEqualTo(mapper.readTree(first));assertThat(snapshot()).isEqualTo(after);
    UUID other=projects.create(actor,workspace,"合成隔离项目",key()).id();
    http.perform(get("/projects/"+other+"/agent/proposals/"+v.id()).with(user(login))).andExpect(status().isNotFound());
    db.update("UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));
    http.perform(post(url).with(user(login)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  }
  @Test void analysisBaseChangeAndInvalidTargetCannotProduceExecutableProposal(){
    UUID run=analyze();var patch=new TaskRequests.Patch();patch.setPriority(0);tasks.update(actor,project,task.id(),task.revision(),patch);
    rejected(()->proposals.create(actor,project,run),"PROPOSAL_ANALYSIS_REQUIRED");
    var accepted=tasks.create(actor,project,new TaskRequests.Create("已接纳任务","合成",TaskStatus.TODO,null,null,null,null),key());
    rejected(()->runs.createTriage(actor,project,accepted.id(),new AgentRuns.Request("分析"),key()),"VALIDATION_ERROR");
    rejected(()->runs.createTriage(member,project,task.id(),new AgentRuns.Request("分析"),key()),"FORBIDDEN");
  }
  @Test void browserEditsPreviewsApprovesAndReopensReceipt()throws Exception {
    var process=new ProcessBuilder("node","scripts/verify-phase5b-ui.cjs",String.valueOf(port),login,project.toString(),task.id().toString(),member.userId().toString()).redirectErrorStream(true).redirectOutput(Path.of("target/phase5b-browser.log").toFile()).start();
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(Long.getLong("workhub.test.browser-timeout-seconds",90L));
    while(process.isAlive() && System.nanoTime()<end){pump();Thread.sleep(150);}
    if(process.isAlive())process.destroyForcibly();
    assertThat(process.waitFor()).as(Files.readString(Path.of("target/phase5b-browser.log"))).isZero();
    assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.TODO);
  }
}
