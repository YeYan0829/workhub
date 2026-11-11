package dev.workhub.conversation;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"spring.datasource.url=jdbc:mysql://127.0.0.1:23316/workhub_conversation_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    "spring.datasource.username=conversation","spring.datasource.password=conversation-local-test",
    "debug=false","workhub.messaging.enabled=false","workhub.retrieval.enabled=false","workhub.embedding.enabled=false","workhub.github.enabled=false","workhub.bootstrap.login=",
    "workhub.agent.enabled=true","workhub.agent.endpoint=http://127.0.0.1:23319/chat/completions","workhub.agent.api-key=fixture-only","workhub.agent.model=glm-4.7",
    "workhub.agent.authorization-id=conversation-it-free-v1","workhub.agent.paid-approved=false","workhub.agent.budget-usd=0","logging.level.root=WARN"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Import(ConversationIT.Config.class)
class ConversationIT {
  @TestConfiguration static class Config {@Bean @Primary Script model(){return new Script();}}
  static class Script implements AgentModel {
    Function<List<Message>,Reply> responder;
    final Queue<Function<List<Message>,Reply>> script=new ConcurrentLinkedQueue<>();final List<List<Message>> inputs=new ArrayList<>();
    public Reply call(List<Message> messages,List<FunctionTool> tools,long remaining){inputs.add(messages);if(responder!=null)return responder.apply(messages);var next=script.poll();if(next==null)throw new AssertionError("Unexpected fixture call");return next.apply(messages);}
  }
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired JdbcTemplate db;@Autowired Json json;@Autowired ObjectMapper mapper;@Autowired ConversationService conversations;
  @Autowired ProjectService projects;@Autowired TaskService tasks;@Autowired ContentService content;@Autowired AgentRuns runs;
  @Autowired WorkJobExecutor executor;@Autowired OutboxRepository outbox;@Autowired Script model;@Autowired ContextPolicy policy;
  @Autowired AgentSettings settings;@Autowired AgentEngine engine;@Autowired RunAgentHandler handler;@Autowired PlatformTransactionManager manager;
  ActorContext actor,other;UUID project,workspace,task;
  @BeforeEach void setup(){
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_conversation_test");
    model.script.clear();model.inputs.clear();model.responder=null;policy.triggerBytes=60000;policy.recentTurns=2;policy.maxCompactions=2;settings.maxRequestInputBound=256000;
    actor=new ActorContext(UUID.randomUUID());other=new ActorContext(UUID.randomUUID());workspace=UUID.randomUUID();
    for(var a:List.of(actor,other))db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",bytes(a.userId()),"conv-"+a.userId(),"Conversation fixture");
    db.update("INSERT INTO workspace(id,name) VALUES (?,'Conversation isolated fixture')",bytes(workspace));
    for(var a:List.of(actor,other))db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(a.userId()));
    project=projects.create(actor,workspace,"连续对话夹具项目",UUID.randomUUID().toString()).id();
    projects.member(actor,project,other.userId(),ProjectRole.MAINTAINER,true,projects.get(actor,project).businessRevision());
    task=tasks.create(actor,project,new TaskRequests.Create("发布任务","发布前必须完成检查。",TaskStatus.TODO,TaskType.TASK,1,actor.userId(),null),UUID.randomUUID().toString()).id();
  }
  AgentModel.Reply reply(AssistantMessage a,String finish){return new AgentModel.Reply(a,100,60,finish,"glm-4.7",Map.of());}
  AgentModel.Reply text(Object body){return reply(new AssistantMessage(json.write(body)),"stop");}
  AgentModel.Reply tool(String name,Map<String,Object> args){return reply(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,json.write(args)))).build(),"tool_calls");}
  void answer(String value){model.script.add(q->tool("get_task",Map.of("taskId",task)));model.script.add(q->{String ref=source(q,"FACT");return text(Map.of("paragraphs",List.of(Map.of("text",value,"citations",List.of(ref)))));});}
  String source(List<Message> q,String kind){for(var m:q)if(m instanceof ToolResponseMessage t)for(var r:t.getResponses())try{for(var c:mapper.readTree(r.responseData()).path("citations"))if(c.path("kind").asText().equals(kind))return c.path("id").asText();}catch(Exception e){throw new RuntimeException(e);}throw new AssertionError("Missing source "+kind);}
  ConversationService.Receipt submit(UUID c,String q){return conversations.submit(actor,c,new ConversationService.Submit(q,conversations.get(actor,c).historyRevision()),UUID.randomUUID().toString(),null);}
  void pump(){for(int i=0;i<30;i++){
    var rows=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler='RUN_AGENT' AND o.consume_disposition='UNHANDLED' AND j.status NOT IN ('DONE','FAILED') ORDER BY o.created_at,o.id",bytes(project));if(rows.isEmpty())return;
    for(var r:rows){var e=outbox.find(uuid((byte[])r.get("id")),false);db.update("UPDATE outbox_message SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",bytes(e.id()));db.update("UPDATE work_job SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",bytes(e.jobId()));e=outbox.find(e.id(),false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(StandardCharsets.UTF_8));}
  }throw new AssertionError("jobs did not drain");}
  long count(String table,UUID c){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_id=?",Long.class,bytes(c));}
  void success(UUID run){assertThat(runs.get(actor,project,run)).satisfies(r->{assertThat(r.status()).as(json.write(r)).isEqualTo("SUCCEEDED");});}
  @Test void actualHistoryFreshToolsAndOldAliases(){
    UUID c=conversations.create(actor,project).id();answer("发布任务当前待办");var first=submit(c,"只看发布，保留约束：不改任务");pump();success(first.runId());
    db.update("UPDATE task SET status='IN_PROGRESS',revision=revision+1 WHERE id=?",bytes(task));
    model.script.add(q->{assertThat(json.write(q)).contains("不改任务","发布任务当前待办",task.toString(),"runLocalAliases");return tool("get_task",Map.of("taskId",task));});
    model.script.add(q->{assertThat(json.write(q)).contains("IN_PROGRESS");return text(Map.of("paragraphs",List.of(Map.of("text","它现在进行中，仍不修改任务。","citations",List.of(source(q,"FACT"))))));});
    var second=submit(c,"那它现在呢？");pump();success(second.runId());
    var page=conversations.messages(actor,c,null,20);assertThat(page.items()).hasSize(2);assertThat(page.items().get(0).sourceState()).isEqualTo("CHANGED");
    assertThat(runs.get(actor,project,first.runId()).status()).isEqualTo("STALE");
    assertThat(conversations.get(actor,c).activeTurnId()).isNull();assertThat(count("agent_message",c)).isEqualTo(4);
  }
  @Test void idempotencyPrecedesRevisionAndConcurrentTabs()throws Exception{
    UUID c=conversations.create(actor,project).id();var request=new ConversationService.Submit("问题",0);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{var a=pool.submit(()->{start.await();return conversations.submit(actor,c,request,"same-key",null);});var b=pool.submit(()->{start.await();return conversations.submit(actor,c,request,"same-key",null);});start.countDown();assertThat(a.get()).isEqualTo(b.get());}finally{pool.shutdownNow();}
    assertThat(count("agent_message",c)).isEqualTo(1);assertThat(count("agent_run",c)).isEqualTo(1);
    assertThatThrownBy(()->conversations.submit(actor,c,request,"other-key",null)).isInstanceOf(ApiException.class).hasMessageContaining("其他窗口");
    assertThatThrownBy(()->submit(c,"并发问题")).isInstanceOf(ApiException.class).hasMessageContaining("正在回答");
  }
  @Test void privateToOwnerIncludingMaintainerAndRevocation(){
    UUID c=conversations.create(actor,project).id();var r=submit(c,"私人标题");
    assertThatThrownBy(()->conversations.get(other,c)).isInstanceOf(ApiException.class);assertThatThrownBy(()->runs.get(other,project,r.runId())).isInstanceOf(ApiException.class);
    assertThat(conversations.list(other,null,false,"私人",null,20).items()).isEmpty();
    db.update("UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));
    assertThat(conversations.list(actor,null,false,"私人",null,20).items()).isEmpty();assertThatThrownBy(()->conversations.messages(actor,c,null,20)).isInstanceOf(ApiException.class);
    assertThat(conversations.delete(actor,c).get("cleanupState")).isEqualTo("CLEANED");assertThat(count("agent_message",c)).isZero();
  }
  @Test void separateProjectsAndConversationMemory(){
    UUID c=conversations.create(actor,project).id();answer("发布任务待办");submit(c,"私人暗号蓝鲸");pump();
    UUID p2=projects.create(actor,workspace,"第二项目",UUID.randomUUID().toString()).id(),c2=conversations.create(actor,p2).id();
    assertThat(conversations.messages(actor,c2,null,20).items()).isEmpty();assertThat(conversations.list(actor,p2,false,"",null,20).items()).extracting(ConversationService.View::id).containsExactly(c2);
    UUID c3=conversations.create(actor,project).id();model.script.add(q->{assertThat(json.write(q)).doesNotContain("蓝鲸");return text(Map.of("type","NEEDS_CLARIFICATION","question","请说明查询对象？","candidates",List.of()));});var r=submit(c3,"它呢？");pump();success(r.runId());
  }
  @Test void cancelRetrySingleUserAndLateResultsCannotPublish(){
    UUID c=conversations.create(actor,project).id();var r=submit(c,"慢问题");
    model.script.add(q->{conversations.cancel(actor,c,r.turnId());return text(Map.of("type","NEEDS_CLARIFICATION","question","迟到回答？","candidates",List.of()));});pump();
    assertThat(conversations.messages(actor,c,null,20).items().get(0).status()).isEqualTo("CANCELED");assertThat(count("agent_message",c)).isEqualTo(1);
    answer("重试已查证");var retried=conversations.submit(actor,c,new ConversationService.Submit(null,conversations.get(actor,c).historyRevision()),"retry",r.turnId());pump();success(retried.runId());
    assertThat(count("agent_message",c)).isEqualTo(2);assertThat(count("agent_run",c)).isEqualTo(2);assertThat(conversations.messages(actor,c,null,20).items().get(0).attempts()).hasSize(2);
  }
  @Test void deletionDuringModelDoesNotRecreateTraceOrContent(){
    UUID c=conversations.create(actor,project).id();var r=submit(c,"删除敏感文字");model.script.add(q->{conversations.delete(actor,c);return text(Map.of("paragraphs",List.of(Map.of("text","迟到正文","citations",List.of()))));});pump();
    assertThat(count("agent_message",c)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=?",Integer.class,bytes(r.runId()))).isZero();
    assertThat(db.queryForObject("SELECT question FROM agent_run WHERE id=?",String.class,bytes(r.runId()))).isEmpty();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Integer.class,bytes(r.runId()))).isEqualTo(1);
  }
  @Test void archiveSearchPaginationAndLegacyMigration(){
    UUID c=conversations.create(actor,project).id();conversations.edit(actor,c,new ConversationService.Edit("可检索标题",true,0));
    assertThat(conversations.list(actor,project,true,"可检索",null,1).items()).hasSize(1);assertThatThrownBy(()->submit(c,"不能发送")).isInstanceOf(ApiException.class);
    conversations.edit(actor,c,new ConversationService.Edit(null,false,1));
    answer("历史答案");var r=runs.create(actor,project,new AgentRuns.Request("旧独立问题"),UUID.randomUUID().toString());pump();success(r.id());
    var imported=conversations.migrate(actor,project,r.id());assertThat(conversations.migrate(actor,project,r.id()).id()).isEqualTo(imported.id());assertThat(conversations.messages(actor,imported.id(),null,20).items()).hasSize(1);
  }
  @Test void independentClarificationDoesNotPermitPlaceholderBusinessAnswers(){
    UUID c=conversations.create(actor,project).id();model.script.add(q->text(Map.of("type","NEEDS_CLARIFICATION","question","两个同名任务，指哪一个？","candidates",List.of("第一个","第二个"))));var r=submit(c,"它是谁负责？");pump();success(r.runId());
    assertThat(runs.get(actor,project,r.runId()).result().answer().claims().get(0).kind()).isEqualTo("CLARIFICATION");
    for(int i=0;i<3;i++)model.script.add(q->text(Map.of("paragraphs",List.of(Map.of("text","占位","citations",List.of())))));var bad=submit(c,"实际业务回答");pump();assertThat(runs.get(actor,project,bad.runId()).errorCode()).isEqualTo("AGENT_NO_PROGRESS");assertThat(conversations.get(actor,c).activeTurnId()).isNull();
  }
  String summary(String constraint){return json.write(Map.of("goals",List.of("了解发布"),"constraints",List.of(constraint),"corrections",List.of("以最新纠正为准"),"objects",List.of(task.toString()),"unresolved",List.of(),"observations",List.of("历史观察，非当前事实"),"readback",List.of("从第1轮回读")));}
  @Test void incrementalCompactionTwiceKeepsOriginalsCorrectionsAndPurpose(){
    UUID c=conversations.create(actor,project).id();policy.triggerBytes=4000;
    for(int round=0;round<8;round++){
      final int current=round;model.script.add(q->{
        if(q.get(0).getText().contains("整理较早会话")){var input=json.write(q);assertThat(input).contains("蓝鲸");model.script.add(q2->tool("get_task",Map.of("taskId",task)));return reply(new AssistantMessage(summary(input.contains("改为周五")?"蓝鲸改为周五":"蓝鲸周三")),"stop");}
        return tool("get_task",Map.of("taskId",task));
      });
      // Flexible deterministic response handles optional summary before answer/tool.
      model.script.add(q->{if(q.get(q.size()-1) instanceof ToolResponseMessage)return text(Map.of("paragraphs",List.of(Map.of("text","已读发布任务。"+"历史说明文字。".repeat(50),"citations",List.of(source(q,"FACT"))))));return tool("get_task",Map.of("taskId",task));});
      model.script.add(q->text(Map.of("paragraphs",List.of(Map.of("text","保留蓝鲸约束，当前任务待办。"+"历史说明。".repeat(50),"citations",List.of(source(q,"FACT")))))));
      var r=submit(c,round==0?"约束：蓝鲸周三":round==2?"纠正蓝鲸，改为周五":"继续核对第"+round+"项");pump();success(r.runId());model.script.clear();
    }
    assertThat(count("agent_context_summary",c)).isGreaterThanOrEqualTo(2);assertThat(count("agent_message",c)).isEqualTo(16);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call m JOIN agent_run r ON r.id=m.run_id WHERE r.conversation_id=? AND purpose='COMPACTION'",Integer.class,bytes(c))).isGreaterThanOrEqualTo(2);
    var body=db.queryForObject("SELECT body FROM agent_context_summary WHERE conversation_id=? ORDER BY version DESC LIMIT 1",String.class,bytes(c));assertThat(body).contains("周五");
  }
  @Test void hardLimitAndFailureLeaveQuestionVisible(){
    UUID c=conversations.create(actor,project).id();settings.maxRequestInputBound=300;var r=submit(c,"问题仍然保留");pump();assertThat(runs.get(actor,project,r.runId()).errorCode()).isEqualTo("AGENT_REQUEST_BUDGET");assertThat(model.inputs).isEmpty();assertThat(conversations.messages(actor,c,null,20).items().get(0).text()).isEqualTo("问题仍然保留");assertThat(conversations.get(actor,c).activeTurnId()).isNull();
  }
  @Test void actualProcessCrashBeforeAndAfterDurableToolBoundary()throws Exception {
    String savedEndpoint=settings.endpoint,savedAuthorization=settings.authorization;
    try(var server=new okhttp3.mockwebserver.MockWebServer()){
      server.start(0);
      org.springframework.test.util.ReflectionTestUtils.setField(settings,"endpoint",server.url("/chat/completions").toString());
      org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization","conversation-crash-"+UUID.randomUUID());
      server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){
        var message=Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","crash_tool","type","function","function",Map.of("name","get_task","arguments",json.write(Map.of("taskId",task))))));
        return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody(json.write(Map.of("id","crash-fixture","model","glm-4.7","choices",List.of(Map.of("index",0,"message",message,"finish_reason","tool_calls")),"usage",Map.of("prompt_tokens",100,"completion_tokens",60,"total_tokens",160))));
      }});
      for(String phase:List.of("BEFORE","AFTER")){
        UUID c=conversations.create(actor,project).id();var r=submit(c,"实际进程崩溃 "+phase);
        var process=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-Xms32m","-Xmx192m","-XX:ActiveProcessorCount=2","-Dconversation.crash="+phase,"-Dconversation.endpoint="+settings.endpoint,"-Dconversation.authorization="+settings.authorization,"-cp",System.getProperty("java.class.path"),CrashWorker.class.getName(),r.runId().toString()).redirectErrorStream(true).redirectOutput(new java.io.File("target/conversation-crash-"+phase+".log")).start();
        try{assertThat(process.waitFor(150,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isEqualTo(86);}finally{if(process.isAlive())process.destroyForcibly();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Integer.class,bytes(r.runId()))).isEqualTo(1);
        db.update("UPDATE work_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE owner_ref=?",bytes(r.runId()));
        if(phase.equals("AFTER"))model.script.add(q->{assertThat(q.stream().anyMatch(m->m instanceof ToolResponseMessage)).isTrue();return text(Map.of("paragraphs",List.of(Map.of("text","从已持久化工具批次继续，不重复第一次模型调用。","citations",List.of(source(q,"FACT"))))));});
        pump();
        if(phase.equals("AFTER")){success(r.runId());assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Integer.class,bytes(r.runId()))).isEqualTo(2);}
        else{assertThat(runs.get(actor,project,r.runId()).errorCode()).isEqualTo("CHECKPOINT_RESPONSE_LOST");assertThat(model.script).isEmpty();}
        assertThat(conversations.get(actor,c).activeTurnId()).isNull();
      }
    }finally{org.springframework.test.util.ReflectionTestUtils.setField(settings,"endpoint",savedEndpoint);org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization",savedAuthorization);}
  }
  @Test void boundedHistoryReadUsesIndependentHistorySources(){
    UUID c=conversations.create(actor,project).id();answer("首轮已查证发布状态");submit(c,"蓝鲸计划必须保留");pump();
    model.script.add(q->tool("read_conversation_messages",Map.of("fromTurn",1,"limit",1)));
    model.script.add(q->{assertThat(json.write(q)).contains("HISTORY_MESSAGE","蓝鲸计划必须保留");return text(Map.of("paragraphs",List.of(Map.of("text","你在第一轮提出蓝鲸计划约束。","citations",List.of(source(q,"HISTORY_MESSAGE"))))));});
    var r=submit(c,"回读我的第一轮要求");pump();success(r.runId());assertThat(runs.get(actor,project,r.runId()).result().sources()).extracting(AgentContracts.Citation::kind).containsExactly("HISTORY_MESSAGE");
  }
  @Test void deletedDocumentHidesHistoryAndInvalidatesDerivedSummary(){
    UUID doc=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"原始约束","删除后不可继续展示的正文",UUID.randomUUID().toString()).id();
    UUID c=conversations.create(actor,project).id();model.script.add(q->tool("read_content",Map.of("objectType","DOCUMENT","objectId",doc,"length",1000)));
    model.script.add(q->text(Map.of("paragraphs",List.of(Map.of("text","历史正文","citations",List.of(source(q,"EVIDENCE")))))));var r=submit(c,"文档写了什么？");pump();success(r.runId());
    db.update("UPDATE project_document SET deleted=TRUE WHERE id=?",bytes(doc));
    var turn=conversations.messages(actor,c,null,20).items().get(0);assertThat(turn.sourceState()).isEqualTo("UNAVAILABLE");assertThat(turn.result()).isNull();
  }

  @Test void unknownReservationSurvivesRecoveryWithoutAnotherSend(){
    UUID c=conversations.create(actor,project).id();var r=submit(c,"未知费用保留");UUID attempt=UUID.randomUUID();
    db.update("UPDATE agent_run SET status='RUNNING',attempt_token=? WHERE id=?",bytes(attempt),bytes(r.runId()));
    db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,'START',?)",bytes(r.runId()),bytes(attempt),json.write(Map.of("deadlineAt",java.time.Instant.now().plusSeconds(240))));
    var audit=new AgentAudit(db,json,settings);UUID call=new TransactionTemplate(manager).execute(st->audit.reserve(r.runId(),attempt,100,100));
    pump();assertThat(model.inputs).isEmpty();assertThat(runs.get(actor,project,r.runId()).errorCode()).isEqualTo("MODEL_RESULT_UNKNOWN");
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE id=?",String.class,bytes(call))).isEqualTo("RESERVED");assertThat(conversations.get(actor,c).activeTurnId()).isNull();
  }
  @Test void summaryFailureFallsBackOnlyWithinHardCapacity(){
    UUID c=conversations.create(actor,project).id();policy.triggerBytes=4000;
    for(int i=0;i<3;i++){answer("已核对发布。"+"较长的历史说明。".repeat(100));var r=submit(c,"早期约束蓝鲸"+i);pump();success(r.runId());}
    model.script.add(q->{assertThat(q.get(0).getText()).contains("整理较早会话");return text(Map.of("invalid","not a summary"));});
    answer("整理失败仍保留完整原文，重新查证完成");var next=submit(c,"继续");pump();success(next.runId());
    assertThat(count("agent_context_summary",c)).isZero();assertThat(count("agent_message",c)).isEqualTo(8);
    assertThat(db.queryForObject("SELECT context_manifest FROM agent_run WHERE id=?",String.class,bytes(next.runId()))).contains("\"dependencies\"",task.toString());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=? AND purpose='COMPACTION'",Integer.class,bytes(next.runId()))).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"cancel","delete","revoke"})
  void lateCompactionCannotPublishAfterLifecycleChange(String action){
    UUID c=conversations.create(actor,project).id();policy.triggerBytes=4000;
    for(int i=0;i<3;i++){answer("已核对发布。"+"较长的历史说明。".repeat(100));var r=submit(c,"早期约束蓝鲸"+i);pump();success(r.runId());}
    model.script.add(q->{
      assertThat(q.get(0).getText()).contains("整理较早会话");
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if(action.equals("cancel"))conversations.cancel(actor,c,conversations.get(actor,c).activeTurnId());
      else if(action.equals("delete"))conversations.delete(actor,c);
      else db.update("UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));
      return text(Map.of("goals",List.of("核查发布"),"constraints",List.of("蓝鲸"),"corrections",List.of(),"objects",List.of(),"unresolved",List.of(),"observations",List.of("历史观察"),"readback",List.of("第1轮")));
    });
    var next=submit(c,"触发整理并改变生命周期");pump();
    assertThat(count("agent_context_summary",c)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=? AND purpose='COMPACTION' AND state='COMPLETE'",Integer.class,bytes(next.runId()))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT active_turn_id FROM agent_conversation WHERE id=?",byte[].class,bytes(c))).isNull();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE turn_id=? AND role='ASSISTANT'",Integer.class,bytes(next.turnId()))).isZero();
    if(action.equals("delete")){assertThat(count("agent_message",c)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=?",Integer.class,bytes(next.runId()))).isZero();}
    if(action.equals("revoke"))assertThatThrownBy(()->conversations.get(actor,c)).isInstanceOf(ApiException.class);
  }

  @Test void insufficientBudgetStopsBeforeSendAndKeepsTheTurn(){
    String authorization=settings.authorization;long price=settings.inputPrice;
    try{
      org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization","conversation-zero-budget-"+UUID.randomUUID());
      org.springframework.test.util.ReflectionTestUtils.setField(settings,"inputPrice",1L);
      UUID c=conversations.create(actor,project).id();var r=submit(c,"额度不足仍保留本轮");pump();
      assertThat(runs.get(actor,project,r.runId()).errorCode()).isEqualTo("MODEL_COST_BUDGET");assertThat(model.inputs).isEmpty();assertThat(conversations.messages(actor,c,null,20).items().get(0).text()).isEqualTo("额度不足仍保留本轮");assertThat(conversations.get(actor,c).activeTurnId()).isNull();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Integer.class,bytes(r.runId()))).isZero();
    }finally{org.springframework.test.util.ReflectionTestUtils.setField(settings,"authorization",authorization);org.springframework.test.util.ReflectionTestUtils.setField(settings,"inputPrice",price);}
  }

  @Test void longToolLoopCompactsCompleteBatchesAndRetainsCitationRegistry(){
    policy.triggerBytes=4000;var patch=new TaskRequests.Patch();patch.setDescription("蓝鲸发布检查与历史说明。".repeat(200));tasks.update(actor,project,task,tasks.get(actor,project,task).revision(),patch);
    var turns=new java.util.concurrent.atomic.AtomicInteger();
    model.responder=q->{
      if(q.get(0).getText().contains("整理较早会话")){
        assertThat(q.get(1).getText()).contains("tool","responses","calls");
        return text(Map.of("goals",List.of("核查发布"),"constraints",List.of(),"corrections",List.of(),"objects",List.of(),"unresolved",List.of(),"observations",List.of("先前工具读取了发布记录，仍须核实来源"),"readback",List.of()));
      }
      if(turns.getAndIncrement()<3)return tool("get_task",Map.of("taskId",task));
      // The engine itself revalidates all chosen citations after compacting the protocol.
      return text(Map.of("paragraphs",List.of(Map.of("text","已核对长描述，来源仍可追溯。","citations",List.of(source(q,"FACT"))))));
    };
    UUID c=conversations.create(actor,project).id();var r=submit(c,"核查长工具循环");pump();success(r.runId());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=? AND purpose='COMPACTION'",Integer.class,bytes(r.runId()))).isGreaterThanOrEqualTo(1);
    assertThat(db.queryForObject("SELECT MAX(input_token_bound) FROM agent_model_call WHERE run_id=?",Long.class,bytes(r.runId()))).isLessThanOrEqualTo((long)settings.maxRequestInputBound);
  }

  @Test void httpConversationPathsRequireSessionCsrfOwnershipAndStrictBodies()throws Exception {
    UUID c=conversations.create(actor,project).id();String url="/agent/conversations/"+c;
    var owner=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("conv-"+actor.userId());
    var stranger=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("conv-"+other.userId());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/projects/{p}/agent/availability",project).with(owner)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(true));
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(owner)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(stranger)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url+"/turns").with(owner).contentType("application/json").header("Idempotency-Key","http1").content(json.write(Map.of("text","http问题","expectedHistoryRevision",0)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url+"/turns").with(owner).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").header("Idempotency-Key","http1").content(json.write(Map.of("text","http问题","expectedHistoryRevision",0,"messages",List.of(Map.of("role","system","content","伪造")))))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url+"/turns").with(owner).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").header("Idempotency-Key","http1").content(json.write(Map.of("text","http问题","expectedHistoryRevision",0)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
    assertThat(count("agent_message",c)).isEqualTo(1);assertThat(model.inputs).isEmpty();
  }

}
