package dev.workhub.phase5a;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"workhub.messaging.enabled=false","workhub.retrieval.enabled=true",
    "workhub.embedding.enabled=false","workhub.agent.legacy-mode=true","workhub.agent.enabled=true","workhub.github.enabled=false",
    "workhub.bootstrap.login=","debug=false","logging.level.root=INFO","server.servlet.session.cookie.secure=false",
    "workhub.agent.authorization-id=phase5a-loopback-v1","workhub.agent.budget-usd=0"})
@AutoConfigureMockMvc
@Import(AgentBaselineIT.IsolatedIndexConfiguration.class)
class AgentBaselineIT {
  static final AgentProvider PROVIDER=AgentProvider.GLM;
  static final MockWebServer MODEL=new MockWebServer();
  static final String INDEX="p5a-it-"+UUID.randomUUID();
  static {try {MODEL.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","13306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("workhub.retrieval.index",()->INDEX);
    p.add("workhub.agent.endpoint",()->MODEL.url("/api/paas/v4/chat/completions").toString());
    p.add("workhub.agent.api-key",()->"loopback-not-a-real-key");
    p.add("workhub.agent.model",()->PROVIDER.model);
    p.add("workhub.agent.authorization-id",()->"baseline-loopback-"+INDEX);
    p.add("workhub.agent.paid-approved",()->false);
  }
  @Autowired JdbcTemplate db; @Autowired ObjectMapper mapper; @Autowired Json json;
  @Autowired ProjectService projects; @Autowired TaskService tasks; @Autowired ContentService content;
  @Autowired DependencyService dependencies; @Autowired AgentRuns runs; @Autowired AgentTools tools;
  @Autowired AgentEngine engine; @Autowired WorkJobExecutor executor; @Autowired OutboxRepository outbox;
  @Autowired MockMvc http; @Autowired PlatformTransactionManager manager;
  @Autowired RunAgentHandler handler;
  @Autowired FixtureIndex index;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  ActorContext actor,member;UUID project,workspace;Map<String,UUID> ids;
  List<Function<JsonNode,Object>> script;AtomicInteger calls;
  volatile Throwable scriptFailure;

  @BeforeEach void setup()throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_test");
    actor=new ActorContext(UUID.randomUUID());member=new ActorContext(UUID.randomUUID());workspace=UUID.randomUUID();
    for(var a:List.of(actor,member))db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",bytes(a.userId()),login(a),"Phase 5 tester");
    db.update("INSERT INTO workspace(id,name) VALUES (?,'Phase 5 isolated fixture')",bytes(workspace));
    for(var a:List.of(actor,member))db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(a.userId()));
    project=projects.create(actor,workspace,"Phase 5 fixture",UUID.randomUUID().toString()).id();
    index.delegate=new ElasticsearchTextIndex(mapper,"http://127.0.0.1:"+System.getenv().getOrDefault("ELASTICSEARCH_PORT","19200"),INDEX+"-"+project);
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,true,revision());
    ids=new HashMap<>();script=new ArrayList<>();calls=new AtomicInteger();scriptFailure=null;
    MODEL.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
      try{
        int index=calls.getAndIncrement();var n=mapper.readTree(request.getBody().readUtf8());
        assertThat(n.path("tools").toString()).as("tools remain available on every turn").contains("find_documents","read_content","list_comments");
        if(index>=script.size())throw new AssertionError("Unexpected local model call: "+index);
        Object response=script.get(index).apply(n);
        if(response instanceof MockResponse mock)return mock;
        return new MockResponse().setHeader("Content-Type","application/json").setBody(json.write(response));
      }catch(Throwable e){scriptFailure=e;return new MockResponse().setResponseCode(500);}
    }});
  }
  @AfterEach void isolate()throws Exception {
    // Preserve evidence and audit; retire only this fixture's identities so nothing leaks into another trial.
    db.update("UPDATE project_member SET active=FALSE WHERE project_id=?",bytes(project));
    db.update("UPDATE workspace_member SET active=FALSE WHERE workspace_id=?",bytes(workspace));
    for(var a:List.of(actor,member))db.update("UPDATE app_user SET active=FALSE WHERE id=?",bytes(a.userId()));
    Phase5aIndexCleanup.delete(INDEX+"-"+project);
    assertThat(scriptFailure).as("local model script assertions").isNull();
  }
  String login(ActorContext a){return "p5a-"+a.userId();}
  long revision(){return projects.get(actor,project).businessRevision();}
  void fixture()throws Exception {
    var f=mapper.readTree(Files.readString(Path.of("src/test/resources/phase5a/project.json")));
    for(var t:f.path("tasks")) {
      var task=tasks.create(actor,project,new TaskRequests.Create(t.path("title").asText(),t.path("description").asText(),TaskStatus.valueOf(t.path("status").asText()),TaskType.valueOf(t.path("type").asText()),t.path("priority").asInt(),t.path("assigned").asBoolean()?actor.userId():null,null),UUID.randomUUID().toString());ids.put(t.path("ref").asText(),task.id());
    }
    for(var edge:f.path("dependencies"))dependencies.add(actor,project,revision(),new Dependency(ids.get(edge.get(0).asText()),ids.get(edge.get(1).asText())),UUID.randomUUID().toString());
    for(var d:f.path("documents"))ids.put(d.path("ref").asText(),content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,d.path("title").asText(),d.path("body").asText(),UUID.randomUUID().toString()).id());
    for(var c:f.path("comments")){var comment=content.createComment(actor,project,ids.get(c.path("task").asText()),c.path("body").asText(),UUID.randomUUID().toString());if(c.has("ref"))ids.put(c.path("ref").asText(),comment.id());
      db.update("UPDATE comment SET created_at=? WHERE id=?",FixtureTimeline.utc(c.path("createdAt").asText()),bytes(comment.id()));}
    FixtureTimeline.apply(db,project,f,ids);
    pump(Set.of("INDEX_TEXT","PUBLISH_TEXT"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler IN ('INDEX_TEXT','PUBLISH_TEXT') AND status<>'DONE'",Integer.class,bytes(project))).isZero();
  }
  void pump(Set<String> handlers) {
    for(int i=0;i<100;i++) {
      var rows=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND o.consume_disposition='UNHANDLED' AND j.status NOT IN ('DONE','FAILED') ORDER BY o.created_at,o.id",bytes(project));
      boolean did=false;
      for(var r:rows){var e=outbox.find(uuid((byte[])r.get("id")),false);if(!handlers.contains(e.handler()))continue;
        db.update("UPDATE outbox_message SET available_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",bytes(e.id()));
        db.update("UPDATE work_job SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",bytes(e.jobId()));
        e=outbox.find(e.id(),false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(StandardCharsets.UTF_8));did=true;
      }
      if(!did)return;
    }
    fail("Fixture obligations did not drain");
  }
  AgentRuns.View run(String question) {
    var r=runs.create(actor,project,new AgentRuns.Request(question),UUID.randomUUID().toString());pump(Set.of("RUN_AGENT"));return runs.get(actor,project,r.id());
  }
  Object response(Object message,String reason) {return Map.of("id","local-call","model",PROVIDER.model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",reason)),"usage",Map.of("prompt_tokens",100,"completion_tokens",60,"total_tokens",160));}
  Object tool(String name,Map<String,Object> args) {return response(Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","call_"+calls.get(),"type","function","function",Map.of("name",name,"arguments",json.write(args))))),"tool_calls");}
  Object answer(JsonNode request,String text,String kind) {
    var refs=new ArrayList<String>();for(var r:observations(request))for(var c:r.path("citations"))if(c.path("kind").asText().equals(kind))refs.add(c.path("id").asText());
    return finalAnswer(List.of(new Claim(kind,text,refs)));
  }
  List<JsonNode> observations(JsonNode request) {
    var result=new ArrayList<JsonNode>();for(var m:request.path("messages"))try {
      String body=m.path("content").asText();
      if(m.path("role").asText().equals("tool"))result.add(mapper.readTree(body));

    }catch(Exception e){throw new IllegalArgumentException(e);}return result;
  }
  Object finalAnswer(List<Claim> claims){return response(Map.of("role","assistant","content",json.write(Map.of("paragraphs",claims.stream().map(c->Map.of("text",c.text(),"citations",c.citations())).toList()))),"stop");}
  String snapshot(){return json.write(db.queryForList("SELECT BIN_TO_UUID(id) id,title,description,status,priority,revision,discussion_revision FROM task WHERE project_id=? ORDER BY id",bytes(project)))+json.write(db.queryForList("SELECT BIN_TO_UUID(blocker_id) blocker,BIN_TO_UUID(blocked_id) blocked FROM task_dependency WHERE project_id=? ORDER BY blocker_id,blocked_id",bytes(project)))+json.write(db.queryForList("SELECT BIN_TO_UUID(id) id,title,body,revision,deleted FROM project_document WHERE project_id=? ORDER BY id",bytes(project)))+json.write(db.queryForList("SELECT BIN_TO_UUID(id) id,body,revision,deleted FROM comment WHERE project_id=? ORDER BY id",bytes(project)))+json.write(db.queryForList("SELECT BIN_TO_UUID(id) id,url,confirmed_metadata,observed_revision,availability,confirmed_at FROM external_resource WHERE project_id=? ORDER BY id",bytes(project)))+revision();}
  void export(String scenario,AgentRuns.View r,String before)throws Exception {
    var rows=db.queryForList("SELECT kind,payload FROM agent_trace WHERE run_id=? ORDER BY id",bytes(r.id()));var trace=new ArrayList<Object>();
    for(var row:rows)trace.add(Map.of("kind",row.get("kind"),"payload",mapper.readTree((String)row.get("payload"))));
    var mapping=new TreeMap<String,Object>(ids);mapping.put("projectId",project);mapping.put("actorId",actor.userId());
    var modelCalls=db.queryForList("SELECT state,input_token_bound,output_token_bound,input_tokens,output_tokens,"+(PROVIDER==AgentProvider.GLM?"estimated_nano_usd":"estimated_nano_cny")+",elapsed_millis,error_code FROM "+(PROVIDER==AgentProvider.GLM?"agent_model_call":"agent_provider_call")+" WHERE run_id=? ORDER BY created_at",bytes(r.id()));
    var row=new LinkedHashMap<String,Object>();row.put("schemaVersion",1);row.put("scenarioId",scenario);row.put("runId",r.id());row.put("modelMode","loopback-fixture");row.put("mapping",mapping);row.put("outcome",r.result());row.put("status",r.status());row.put("errorCode",r.errorCode());row.put("trace",trace);row.put("modelCalls",modelCalls);row.put("businessUnchanged",before.equals(snapshot()));if(Set.of("source-refresh","comment-refresh-navigation").contains(scenario))row.put("fixtureMutation","Test operator edits the cited document/comment during a model call; this scenario expects a business change and rejects its old citation.");
    Path dest=Path.of(System.getProperty("phase5a.output","target/evidence/phase5a/runs/local-"+INDEX),"results.jsonl");Files.createDirectories(dest.getParent());Files.writeString(dest,json.write(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  }

  @AfterAll static void stopModel()throws Exception{MODEL.shutdown();}
  JsonNode last(JsonNode request){var all=observations(request);return all.get(all.size()-1);}
  Map<String,Object> opening(JsonNode path){assertThat(path.path("tool").asText()).isEqualTo("read_content");return mapper.convertValue(path.path("arguments"),Map.class);}
  String visibleTask(JsonNode request){return last(request).at("/data/items/0/id").asText();}
  Object batch(List<Map.Entry<String,Map<String,Object>>> operations){var batch=new ArrayList<Object>();for(var op:operations)batch.add(Map.of("id","call_"+batch.size(),"type","function","function",Map.of("name",op.getKey(),"arguments",json.write(op.getValue()))));return response(Map.of("role","assistant","tool_calls",batch),"tool_calls");}
  ToolResult invoke(String name,Map<String,Object> args){var a=tools.arguments(name,json.write(args));return name.equals("get_task")?tools.taskContext(actor,project,a):Set.of("read_content","find_documents","list_comments","search_project_evidence").contains(name)?tools.execute(actor,project,name,a):tools.fact(actor,project,name,a);}
  void succeeded(String scenario,AgentRuns.View result,String before)throws Exception {
    assertThat(scriptFailure).isNull();assertThat(result.status()).as(result.errorCode()).isEqualTo("SUCCEEDED");
    assertThat(snapshot()).isEqualTo(before);assertThat(runs.get(actor,project,result.id()).status()).isEqualTo("SUCCEEDED");
    tools.validate(actor,project,revision(),result.result().sources());export(scenario,result,before);
  }
  @Autowired dev.workhub.integration.github.GitHubEvidence githubEvidence;
  @Autowired WorkJobRepository workJobs;
  UUID githubResource(String kind)throws Exception {
    UUID connection=UUID.randomUUID();long repository=connection.getMostSignificantBits()&Long.MAX_VALUE;
    db.update("INSERT INTO github_connection(id,project_id,installation_id,repository_id,repository_name,import_actor) VALUES (?,?,1,?,'fixture/repository',?)",bytes(connection),bytes(project),repository,bytes(actor.userId()));
    return githubEvidence.link(actor,project,ids.get("login"),new dev.workhub.integration.github.GitHubEvidence.Link(connection,tasks.get(actor,project,ids.get("login")).revision(),kind,2,kind.equals("CI_RUN")?1:null,kind.equals("PR")?null:"a".repeat(40)),UUID.randomUUID().toString());
  }
  void publishGithub(UUID resource,String kind,String body,boolean matched)throws Exception {
    UUID id=db.queryForObject("SELECT id FROM work_job WHERE owner_ref=? ORDER BY created_at DESC LIMIT 1",(r,n)->read(r,"id"),bytes(resource));
    var job=workJobs.find(id,false);var call=githubEvidence.prepare(job);
    var raw=mapper.createObjectNode().put("id",33).put("number",matched?2:3).put("title","退款修复").put("body",body).put("merged",true).put("state","closed")
        .put("run_attempt",1).put("head_sha","a".repeat(40)).put("sha",matched?"a".repeat(40):"b".repeat(40)).put("conclusion","failure");
    if(kind.equals("CI_RUN"))raw.put("id",matched?2:3);
    githubEvidence.complete(job,call,new dev.workhub.integration.github.GitHubClient.Reply(200,raw,null,null,null,null));
  }
  @Test void githubOriginalContentCanBeDiscoveredContinuedCitedAndReadBack()throws Exception {
    fixture();UUID resource=githubResource("PR");String body="背景。".repeat(1600)+"最终修复：幂等键加入商户号。";
    publishGithub(resource,"PR",body,true);
    script.add(q->tool("list_tasks",Map.of("query","登录模块")));
    script.add(q->tool("get_task",Map.of("taskId",visibleTask(q))));
    script.add(q->{var path=last(q).at("/data/linkedResources/github/0/readPath");assertThat(path.path("tool").asText()).isEqualTo("read_github_resource");
      Map<String,Object> args=mapper.convertValue(path.path("arguments"),Map.class);args.put("length",4000);return tool(path.path("tool").asText(),args);});
    script.add(q->{var data=last(q).path("data");assertThat(data.path("truncated").asBoolean()).isTrue();assertThat(data.path("text").asText()).doesNotContain("最终修复");
      return tool(data.at("/continue/tool").asText(),mapper.convertValue(data.at("/continue/arguments"),Map.class));});
    script.add(q->{assertThat(last(q).at("/data/text").asText()).contains("最终修复：幂等键加入商户号。");return answer(q,"关联 PR 的修复为幂等键加入商户号。","FACT");});
    String before=snapshot();var result=run("登录模块关联 PR 改了什么？");succeeded("github-readonly-content",result,before);
    assertThat(result.result().sources()).anyMatch(s->s.toolName().equals("read_github_resource")&&s.data().path("text").asText().contains("最终修复"));
    publishGithub(resource,"PR","另一份修复说明",true);
    assertThat(runs.get(actor,project,result.id()).status()).isEqualTo("STALE");
  }
  @Test void githubSnapshotsRejectForeignChangedAndUnverifiedSources()throws Exception {
    fixture();UUID foreign=projects.create(actor,workspace,"foreign",UUID.randomUUID().toString()).id();
    for(String kind:List.of("PR","CI_RUN","COMMIT")) {
      UUID resource=githubResource(kind);publishGithub(resource,kind,"原始说明 😀",true);
      var original=invoke("read_github_resource",Map.of("resourceId",resource));assertThat(original.data().path("text").asText()).contains("原始说明 😀");
      var pinned=mapper.convertValue(original.data().at("/readPath/arguments"),Map.class);
      assertThatThrownBy(()->tools.fact(actor,foreign,"read_github_resource",mapper.valueToTree(pinned))).isInstanceOf(ApiException.class);
      tools.validate(actor,project,revision(),original.citations());
      publishGithub(resource,kind,"正文已修改",true);
      assertThat(tools.invalidSources(actor,project,original.citations())).containsExactly(original.citations().get(0).id());
      assertThatThrownBy(()->invoke("read_github_resource",pinned)).isInstanceOf(ApiException.class).hasMessageContaining("变化");
      assertThat(invoke("read_github_resource",Map.of("resourceId",resource)).data().path("text").asText()).contains("正文已修改");
      publishGithub(resource,kind,"错误远端对象",false);
      assertThatThrownBy(()->invoke("read_github_resource",Map.of("resourceId",resource))).isInstanceOf(ApiException.class).hasMessageContaining("身份核验");
    }
    UUID resource=githubResource("PR");publishGithub(resource,"PR","撤权后不可读",true);
    db.update("UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));
    assertThatThrownBy(()->invoke("read_github_resource",Map.of("resourceId",resource))).isInstanceOf(ApiException.class);
  }
  @Test void businessTextIsPreservedThroughQuestionToolsAnswerAndAudit()throws Exception {
    fixture();String text="client_secret=未配置，因此登录失败；先检查回调，再复测。联系 dev@example.test，样例 sk-synthetic123456789。";
    content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"业务原文",text,UUID.randomUUID().toString());
    script.add(q->{assertThat(q.toString()).contains(text);assertThat(q.toString()).doesNotContain("loopback-not-a-real-key");return tool("find_documents",Map.of("query","业务原文"));});
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains(text);return answer(q,text,"EVIDENCE");});
    String before=snapshot();var result=run(text);succeeded("literal-business-text",result,before);
    assertThat(result.result().answer().claims().get(0).text()).isEqualTo(text);
    String audit=db.queryForList("SELECT payload FROM agent_trace WHERE run_id=?",String.class,bytes(result.id())).toString();
    assertThat(audit).contains(text).doesNotContain("loopback-not-a-real-key");
  }
  @Test void changedCommentCanBeReopenedWithoutLosingNextPage()throws Exception {
    fixture();UUID task=ids.get("login");
    var first=content.comments(actor,project,task,null,10).get(0);
    script.add(q->tool("list_comments",Map.of("taskId",task,"limit",2)));
    script.add(q->{content.editComment(actor,project,task,first.id(),first.revision(),"A 的新结论：仍待复测");return answer(q,"旧评论结论","EVIDENCE");});
    script.add(q->{var page=last(q);assertThat(page.path("invalidatedSources")).hasSize(1);assertThat(page.path("citations")).hasSize(1);
      var reopen=page.path("reopen").get(0);assertThat(reopen.path("readPath").path("arguments").has("sourceRevision")).isFalse();
      return tool("read_content",opening(reopen.path("readPath")));});
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains("A 的新结论");
      var page=observations(q).get(0);var next=page.at("/navigation/nextPage");assertThat(next.path("tool").asText()).isEqualTo("list_comments");
      return tool(next.path("tool").asText(),mapper.convertValue(next.path("arguments"),Map.class));});
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains("traceId");return answer(q,"新结论仍待复测；后续讨论补充 traceId。","EVIDENCE");});
    var result=run("读取评论并核对最新进展");assertThat(result.status()).as(result.errorCode()).isEqualTo("SUCCEEDED");assertThat(calls.get()).isEqualTo(5);
    assertThat(result.result().sources()).noneMatch(s->s.evidence()!=null&&s.evidence().ownerId().equals(first.id())&&s.evidence().sourceRevision()==0);
    assertThat(runs.get(actor,project,result.id()).status()).isEqualTo("SUCCEEDED");export("comment-refresh-navigation",result,"intentional comment edit");
  }
  @Test void fixtureHistoryDoesNotPrecedeItsObjects()throws Exception {
    fixture();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM comment c JOIN task t ON t.id=c.task_id JOIN project p ON p.id=c.project_id WHERE c.project_id=? AND (c.created_at<t.created_at OR t.created_at<p.created_at OR t.updated_at<t.created_at)",Integer.class,bytes(project))).isZero();
  }
  @Test void handoffUsesVisibleNavigationAndCanReadAfterInvalidAnswerOnSeventhTurn()throws Exception {
    fixture();var task=new java.util.concurrent.atomic.AtomicReference<String>();
    script.add(q->tool("list_tasks",Map.of("query","登录模块")));
    script.add(q->{task.set(visibleTask(q));assertThat(task.get()).startsWith("T");return tool("get_task",Map.of("taskId",task.get()));});
    script.add(q->{assertThat(last(q).at("/data/status").asText()).isEqualTo("TODO");return tool("find_documents",Map.of("query","错误关键词不存在"));});
    script.add(q->{assertThat(last(q).at("/data/items")).isEmpty();return tool("find_documents",Map.of("query","登录 设计 复核"));});
    script.add(q->{assertThat(last(q).at("/data/items/0/title").asText()).isEqualTo("tokenTimeout 设计复核");return tool("read_content",opening(last(q).at("/data/items/0/readPath")));});
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains("先核对 OAuth callback","仍不能确定最终根因");return response(Map.of("role","assistant","content","不是合法 JSON"),"stop");});
    script.add(q->{assertThat(q.toString()).contains("不是合法 JSON","paragraphs","TODO");return tool("list_comments",Map.of("taskId",task.get()));});
    script.add(q->{assertThat(last(q).at("/data/returned").asInt()).isEqualTo(3);assertThat(q.toString()).contains("2026-10-01","2026-10-08","2026-10-09");
      var refs=new LinkedHashSet<String>();for(var o:observations(q))for(var c:o.path("citations"))refs.add(c.path("id").asText());
      return finalAnswer(List.of(new Claim("ANSWER","登录模块当前 TODO、优先级 1。设计复核要求先核对 OAuth callback 回调配置，再复测网络重试；最终根因尚未确认。历史 DONE 已被重新打开，最近评论只补充 traceId，没有新的复测结果。",List.copyOf(refs))));});
    String before=snapshot();var query=mapper.readTree(Files.readString(Path.of("src/test/resources/phase5a/project.json"))).path("question").asText();var r=run(query);
    succeeded("handoff",r,before);assertThat(calls.get()).isEqualTo(8);assertThat(r.result().sources()).anyMatch(s->s.evidence()!=null&&s.evidence().kind().equals("COMMENT"));
  }
  @Test void longDocumentAndCommentPaginationPersistTogether()throws Exception {
    fixture();var doc=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"完整阅读","开头。"+"前文。".repeat(1200)+"TAIL_ANCHOR https://example.invalid/callback "+"后文。".repeat(900),UUID.randomUUID().toString());
    content.linkDocument(actor,project,ids.get("login"),tasks.get(actor,project,ids.get("login")).revision(),doc.id(),true);
    var task=new java.util.concurrent.atomic.AtomicReference<String>();
    script.add(q->tool("list_tasks",Map.of("query","登录模块")));
    script.add(q->{task.set(visibleTask(q));return tool("get_task",Map.of("taskId",task.get()));});
    script.add(q->batch(List.of(Map.entry("read_content",opening(last(q).at("/data/linkedResources/links/documents/0/readPath"))),Map.entry("find_documents",Map.of("query","TAIL_ANCHOR")))));
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains("TAIL_ANCHOR");return batch(List.of(Map.entry("read_content",opening(last(q).at("/data/items/0/readPath"))),Map.entry("list_comments",Map.of("taskId",task.get(),"limit",1))));});
    script.add(q->{var os=observations(q);var read=os.get(os.size()-2);var comments=last(q);assertThat(comments.at("/data/hasMore").asBoolean()).isTrue();return batch(List.of(Map.entry("read_content",opening(read.at("/citations/0/continue"))),Map.entry("list_comments",Map.of("taskId",task.get(),"limit",1,"cursor",comments.at("/data/nextCursor").asText()))));});
    script.add(q->answer(q,"文档包含 TAIL_ANCHOR 和回调地址，后文与第二页评论已读。","EVIDENCE"));
    String before=snapshot();succeeded("long-reading",run("阅读登录模块关联长文档及两页评论"),before);assertThat(calls.get()).isEqualTo(6);
  }
  @Test void summaryCountsSurviveStorageButRealCountChangesInvalidate()throws Exception {
    // Match click-03's distribution; the model is scripted, but SQL, jobs, persistence and HTTP are real.
    for(int i=0;i<20;i++)tasks.create(actor,project,new TaskRequests.Create("click-03 regression "+i,null,
        i<3?TaskStatus.TODO:i<11?TaskStatus.DONE:TaskStatus.CANCELED,null,null,null,null),UUID.randomUUID().toString());
    var fresh=invoke("get_project_summary",Map.of());
    var restored=json.read(json.write(fresh),ToolResult.class);
    assertThat(fresh.data().at("/statusCounts/0/count").isLong()).isTrue();
    assertThat(restored.data().at("/statusCounts/0/count").isInt()).isTrue();
    assertThat(json.write(fresh.data())).isEqualTo(json.write(restored.data()));
    assertThat(tools.invalidSources(actor,project,fresh.citations())).isEmpty();
    script.add(q->tool("get_project_summary",Map.of()));
    script.add(q->answer(q,"当前项目 TODO 3、DONE 8、CANCELED 9，共 20 个任务。","FACT"));
    String before=snapshot();var r=run("当前任务状态数量是多少？");
    assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,bytes(r.id()))).isEqualTo("SUCCEEDED");
    succeeded("summary-counts-roundtrip",r,before);
    assertThat(tools.invalidSources(actor,project,restored.citations())).isEmpty();
    String path="/projects/"+project+"/agent/runs/"+r.id();
    http.perform(get(path).with(user(login(actor)))).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.result.answer.claims[0].text").value("当前项目 TODO 3、DONE 8、CANCELED 9，共 20 个任务。"));
    tasks.create(actor,project,new TaskRequests.Create("新增待办",null,TaskStatus.TODO,null,null,null,null),UUID.randomUUID().toString());
    assertThat(tools.invalidSources(actor,project,restored.citations())).containsExactly(restored.citations().get(0).id());
    http.perform(get(path).with(user(login(actor)))).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("STALE"))
        .andExpect(jsonPath("$.errorCode").value("AGENT_CONTEXT_CHANGED"))
        .andExpect(jsonPath("$.result").isEmpty());
  }
  @Test void unchangedJsonAndUnicodeStayValidButRealEditInvalidates()throws Exception {
    fixture();String raw="{\n  \"mode\": \"observe\",\n  \"credential\": \"sk-synthetic123456789\"\n}";
    var document=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"JSON配置",raw,UUID.randomUUID().toString());
    script.add(q->tool("find_documents",Map.of("query","JSON配置")));
    script.add(q->{var args=opening(last(q).at("/data/items/0/readPath"));args.put("start",last(q).at("/citations/0/text").asText().indexOf('\n')+1);return tool("read_content",args);});
    script.add(q->{assertThat(q.toString()).contains("sk-synthetic123456789");assertThat(last(q).at("/citations/0/text").asText()).isEqualTo(raw);return answer(q,"配置 mode 为 observe。","EVIDENCE");});
    String before=snapshot();var r=run("读取配置");succeeded("json-source",r,before);
    assertThat(db.queryForList("SELECT payload FROM agent_trace WHERE run_id=?",String.class,bytes(r.id())).toString()).contains("sk-synthetic123456789");
    var unicode=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"字符边界","😀后文",UUID.randomUUID().toString());
    var chunk=invoke("read_content",Map.of("objectType","DOCUMENT","objectId",unicode.id(),"start",unicode.title().length()+1,"length",1));assertThat(chunk.citations().get(0).evidence().text()).isEqualTo("😀");assertThat(tools.invalidSources(actor,project,chunk.citations())).isEmpty();
    content.editDocument(actor,project,document.id(),document.revision(),document.title(),raw+"changed");assertThat(runs.get(actor,project,r.id()).status()).isEqualTo("STALE");
  }
  @Test void realSearchAndDirectReadShareSourceValidity()throws Exception {
    fixture();var search=invoke("search_project_evidence",Map.of("query","tokenTimeout","strategy","BM25","limit",10));
    var hit=search.citations().stream().filter(c->c.evidence().ownerId().equals(ids.get("design"))).findFirst().orElseThrow();var e=hit.evidence();
    var direct=invoke("read_content",Map.of("objectType",e.kind(),"objectId",e.ownerId(),"sourceRevision",e.sourceRevision(),"start",e.charStart(),"length",e.charEnd()-e.charStart())).citations().get(0);
    tools.validate(actor,project,revision(),List.of(hit,direct));
    var doc=content.getDocument(actor,project,e.ownerId());content.editDocument(actor,project,doc.id(),doc.revision(),doc.title(),"changed");assertThat(tools.invalidSources(actor,project,List.of(hit,direct))).containsExactly(hit.id(),direct.id());
  }
  @Test void unavailableNeighborDoesNotHideReadableDocumentOrComment()throws Exception {
    fixture();var bad=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"部分资料A","失效正文",UUID.randomUUID().toString());content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,"部分资料B","健康正文",UUID.randomUUID().toString());
    db.update("UPDATE knowledge_source SET active=FALSE WHERE project_id=? AND document_id=?",bytes(project),bytes(bad.id()));
    var found=invoke("find_documents",Map.of("query","部分资料"));assertThat(found.data().path("items")).hasSize(2);assertThat(found.data().path("complete").asBoolean()).isFalse();assertThat(found.citations()).hasSize(1);tools.validate(actor,project,revision(),found.citations());
    var comments=invoke("list_comments",Map.of("taskId",ids.get("login")));db.update("UPDATE knowledge_source SET active=FALSE WHERE project_id=? AND comment_id=?",bytes(project),bytes(comments.citations().get(0).evidence().ownerId()));
    var partial=invoke("list_comments",Map.of("taskId",ids.get("login")));assertThat(partial.citations()).hasSize(2);assertThat(partial.data().path("unavailable")).hasSize(1);tools.validate(actor,project,revision(),partial.citations());
  }
  @Test void httpSubmissionHasCsrfIdempotencyAndOwnerIsolation()throws Exception {
    fixture();script.add(q->tool("list_tasks",Map.of("query","登录模块")));script.add(q->answer(q,"登录模块 TODO，优先级 1。","FACT"));String before=snapshot();String path="/projects/"+project+"/agent/runs";
    http.perform(post(path).with(user(login(actor))).contentType("application/json").header("Idempotency-Key","request").content("{\"question\":\"登录模块\"}")).andExpect(status().isForbidden());
    var request=post(path).with(user(login(actor))).with(csrf()).contentType("application/json").header("Idempotency-Key","request").content("{\"question\":\"登录模块\"}");
    var created=http.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    assertThat(mapper.readTree(created).path("question").asText()).isEqualTo("登录模块");
    UUID id=UUID.fromString(mapper.readTree(created).path("id").asText());assertThat(runs.create(actor,project,new AgentRuns.Request("登录模块"),"request").id()).isEqualTo(id);
    pump(Set.of("RUN_AGENT"));succeeded("http",runs.get(actor,project,id),before);http.perform(get(path+"/"+id).with(user(login(member)))).andExpect(status().isNotFound());
    var restored=http.perform(get(path+"/"+id).with(user(login(actor)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(mapper.readTree(restored).path("question").asText()).isEqualTo("登录模块");
  }
  @Test void repeatedUnresearchedAnswersStopAfterThreeCalls()throws Exception {
    fixture();for(int i=0;i<3;i++)script.add(q->finalAnswer(List.of(new Claim("ANSWER","准备查询",List.of()))));
    var r=run("请核对任务进展");assertThat(r.status()).isEqualTo("FAILED");assertThat(r.errorCode()).isEqualTo("AGENT_NO_PROGRESS");assertThat(calls.get()).isEqualTo(3);
  }
  @Test void placeholderAndUnresearchedAnswersMustBeRepairedThroughRealTools()throws Exception {
    fixture();
    script.add(q->finalAnswer(List.of(new Claim("ANSWER","占位",List.of()))));
    script.add(q->{assertThat(q.toString()).contains("不能提交占位文字");return finalAnswer(List.of(new Claim("ANSWER","目前资料不足",List.of())));});
    script.add(q->{assertThat(q.toString()).contains("尚未读取项目资料");return tool("list_tasks",Map.of("query","登录模块"));});
    script.add(q->answer(q,"登录模块当前待办。","FACT"));
    var r=run("登录模块当前进展？");assertThat(r.status()).isEqualTo("SUCCEEDED");
    assertThat(calls.get()).isEqualTo(4);assertThat(r.result().tools()).contains("list_tasks");assertThat(r.result().sources()).isNotEmpty();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='ANSWER_REJECTED'",Integer.class,bytes(r.id()))).isEqualTo(2);
  }
  @Test void writesAndForeignObjectsReturnErrorsWithoutLeakingData()throws Exception {
    fixture();UUID foreign=projects.create(actor,workspace,"Other project",UUID.randomUUID().toString()).id();UUID foreignTask=tasks.create(actor,foreign,new TaskRequests.Create("foreign private",null,null,null,null,null,null),UUID.randomUUID().toString()).id();
    script.add(q->tool("update_task",Map.of("taskId",ids.get("login"),"status","DONE")));
    script.add(q->{assertThat(last(q).has("error")).isTrue();return tool("get_task",Map.of("taskId",foreignTask));});
    script.add(q->{assertThat(q.toString()).doesNotContain("foreign private");assertThat(last(q).has("error")).isTrue();return tool("list_tasks",Map.of("query","登录模块"));});
    script.add(q->answer(q,"当前项目登录模块 TODO。","FACT"));String before=snapshot();succeeded("scope",run("读取项目任务"),before);
  }
  @Test void revocationDuringCallPreventsPublicationAndReadback()throws Exception {
    fixture();script.add(q->{db.update("UPDATE project_member SET active=FALSE WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));return finalAnswer(List.of(new Claim("UNKNOWN","无法确认",List.of())));});
    var r=runs.create(actor,project,new AgentRuns.Request("撤权"),UUID.randomUUID().toString());pump(Set.of("RUN_AGENT"));assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,bytes(r.id()))).isEqualTo("FAILED");assertThatThrownBy(()->runs.get(actor,project,r.id())).isInstanceOf(ApiException.class);
  }
  @Test void changedSourceCannotPublishAnOldCitation()throws Exception {
    fixture();script.add(q->tool("find_documents",Map.of("query","设计复核")));
    script.add(q->{var doc=content.getDocument(actor,project,ids.get("design"));content.editDocument(actor,project,doc.id(),doc.revision(),doc.title(),"新内容尚未验证");return answer(q,"旧设计已确认","EVIDENCE");});
    script.add(q->{assertThat(q.toString()).contains("invalidatedSources");return tool("find_documents",Map.of("query","设计复核"));});
    script.add(q->{assertThat(last(q).at("/citations/0/text").asText()).contains("新内容尚未验证");return answer(q,"新内容尚未验证。","EVIDENCE");});
    String before=snapshot();var result=run("设计说明");assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.result().sources()).allMatch(c->c.evidence()==null||!c.evidence().text().contains("当前排查方案"));assertThat(snapshot()).isNotEqualTo(before);export("source-refresh",result,before);
  }
  @Test void forgedCitationCanBeCorrectedWithMoreReading()throws Exception {
    fixture();script.add(q->finalAnswer(List.of(new Claim("FACT","虚构结论",List.of("S999")))));
    script.add(q->{assertThat(q.toString()).contains("答案未提交");return tool("list_tasks",Map.of("query","登录模块"));});
    script.add(q->answer(q,"登录模块 TODO。","FACT"));String before=snapshot();succeeded("citation-correction",run("任务状态"),before);
  }
  @Test void repeatedReadsRemainPossibleAndExhaustionDoesNotInventAnAnswer()throws Exception {
    fixture();for(int i=0;i<AgentSettings.MAX_ROUNDS;i++)script.add(q->tool("get_project_summary",Map.of()));var r=run("重复读取");assertThat(r.status()).isEqualTo("FAILED");assertThat(r.errorCode()).isEqualTo("AGENT_ROUND_BUDGET");assertThat(calls.get()).isEqualTo(AgentSettings.MAX_ROUNDS);assertThat(r.result().answer()).isNull();
  }
  @Test void slowModelCanContinueBeyondEightCallsAndOneMinute()throws Exception {
    fixture();
    // Exercise real HTTP waiting and the job lease, not a fast script with a larger constant.
    for(int delay:List.of(40,25))script.add(q->new MockResponse().setHeader("Content-Type","application/json")
        .setBody(json.write(tool("list_tasks",Map.of("query","登录模块"))))
        .setBodyDelay(delay,java.util.concurrent.TimeUnit.SECONDS));
    for(int i=0;i<9;i++)script.add(q->tool("list_tasks",Map.of("query","登录模块")));
    script.add(q->answer(q,"登录模块当前为 TODO、P1。","FACT"));
    String before=snapshot();long start=System.nanoTime();var result=run("核对登录模块当前状态");
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)).isGreaterThanOrEqualTo(65);
    assertThat(calls.get()).isEqualTo(12);succeeded("slow-model-capacity",result,before);
  }
  @Test void transportErrorIsNotRetriedAndUnknownChargeIsRetained()throws Exception {
    fixture();script.add(q->new MockResponse().setResponseCode(429).setHeader("Content-Type","application/json").setBody("{\"error\":{\"message\":\"rate limited\"}}"));var r=run("限流");assertThat(r.status()).isEqualTo("FAILED");assertThat(calls.get()).isEqualTo(1);assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(r.id()))).isEqualTo("UNKNOWN");
  }
  @Test void businessSemanticsAllowCompletionDespiteCanceledBlocker()throws Exception {
    fixture();var blockers=invoke("get_direct_blockers",Map.of("taskId",ids.get("login")));assertThat(blockers.data().at("/blockers/0/status").asText()).isEqualTo("CANCELED");
    assertThat(invoke("get_dependency_path",Map.of("fromTaskId",ids.get("infra"),"toTaskId",ids.get("release"))).data().path("path")).hasSize(3);
    var task=tasks.get(actor,project,ids.get("login"));var patch=new TaskRequests.Patch();patch.setStatus(TaskStatus.DONE);
    tasks.update(actor,project,task.id(),task.revision(),patch);assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.DONE);
  }
  @Test void accountingReservesOnlyActualRequestsAndSettlesOnce()throws Exception {
    var r=runningLedger("ledger");var settings=ledgerSettings(r.getKey(),new java.math.BigDecimal("0.001"));settings.campaignBaseline=0;settings.campaignLimit=1;
    var audit=new AgentAudit(db,json,settings);var tx=new TransactionTemplate(manager);
    UUID call=tx.execute(s->audit.reserve(r.getKey(),r.getValue(),10,10));long reserved=10*settings.inputPrice+10*settings.outputPrice;
    assertThat(balance(settings)).isEqualTo(reserved);tx.executeWithoutResult(s->audit.finish(call,5L,3L,1,null));long actual=5*settings.inputPrice+3*settings.outputPrice;assertThat(balance(settings)).isEqualTo(actual);
    tx.executeWithoutResult(s->audit.finish(call,5L,3L,1,null));assertThat(balance(settings)).isEqualTo(actual);
    assertThatThrownBy(()->tx.execute(s->audit.reserve(r.getKey(),r.getValue(),1,1))).hasFieldOrPropertyWithValue("code","AGENT_CAMPAIGN_LIMIT");assertThat(balance(settings)).isEqualTo(actual);
  }
  @Test void unknownChargesAndPreviousAttemptCannotReuseMoney()throws Exception {
    var r=runningLedger("unknown");var settings=ledgerSettings(r.getKey(),java.math.BigDecimal.ONE);var audit=new AgentAudit(db,json,settings);var tx=new TransactionTemplate(manager);
    UUID call=tx.execute(s->audit.reserve(r.getKey(),r.getValue(),10,10));long held=balance(settings);tx.executeWithoutResult(s->audit.finish(call,null,null,1,"TRANSPORT_UNKNOWN"));tx.executeWithoutResult(s->audit.finish(call,1L,1L,1,null));assertThat(balance(settings)).isEqualTo(held);
    db.update("UPDATE agent_run SET attempt_token=? WHERE id=?",bytes(UUID.randomUUID()),bytes(r.getKey()));assertThatThrownBy(()->tx.execute(s->audit.reserve(r.getKey(),r.getValue(),1,1))).hasFieldOrPropertyWithValue("code","AGENT_ATTEMPT_SUPERSEDED");assertThat(balance(settings)).isEqualTo(held);
    assertThatThrownBy(()->tx.execute(s->{audit.toolAttempt(r.getKey(),r.getValue(),"list_tasks","call","{}");return null;})).hasFieldOrPropertyWithValue("code","AGENT_ATTEMPT_SUPERSEDED");
  }
  @Test void concurrentReservationsCannotOverspendOrChangeBillingContract()throws Exception {
    var a=runningLedger("concurrent A");var b=runningLedger("concurrent B");var settings=ledgerSettings(a.getKey(),new java.math.BigDecimal("0.0001"));var audit=new AgentAudit(db,json,settings);var tx=new TransactionTemplate(manager);
    var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var results=new ArrayList<java.util.concurrent.Future<String>>();for(var pair:List.of(a,b))results.add(pool.submit(()->{gate.await();try{tx.execute(s->audit.reserve(pair.getKey(),pair.getValue(),10,10));return "reserved";}catch(ApiException e){return e.code;}}));gate.countDown();
      var outcomes=new ArrayList<String>();for(var result:results)outcomes.add(result.get(10,java.util.concurrent.TimeUnit.SECONDS));assertThat(outcomes).containsExactlyInAnyOrder("reserved","MODEL_COST_BUDGET");assertThat(balance(settings)).isEqualTo(100000);
      var changed=new AgentSettings(true,true,settings.endpoint,"never-sent",settings.model,settings.authorization,java.math.BigDecimal.ONE,2000,8000);var other=new AgentAudit(db,json,changed);assertThatThrownBy(()->tx.execute(s->other.reserve(a.getKey(),a.getValue(),1,1))).hasFieldOrPropertyWithValue("code","MODEL_BUDGET_CONTRACT_CHANGED");assertThat(balance(settings)).isEqualTo(100000);
    }finally{pool.shutdownNow();}
  }
  @Test void expiredJobDoesNotStartAnotherModelCall()throws Exception {
    var r=runs.create(actor,project,new AgentRuns.Request("deadline"),UUID.randomUUID().toString());var jobId=db.queryForObject("SELECT id FROM work_job WHERE owner_ref=? AND handler='RUN_AGENT'",(row,n)->read(row,"id"),bytes(r.id()));var job=new WorkJobRepository(db).find(jobId,false);var tx=new TransactionTemplate(manager);tx.execute(s->handler.prepare(job));
    db.update("UPDATE agent_trace SET payload=? WHERE run_id=? AND kind='START'",json.write(Map.of("deadlineAt",java.time.Instant.now().minusSeconds(1))),bytes(r.id()));tx.execute(s->handler.prepare(job));assertThat(runs.get(actor,project,r.id()).errorCode()).isEqualTo("AGENT_TIMEOUT");assertThat(calls.get()).isZero();
  }
  Map.Entry<UUID,UUID> runningLedger(String question){var r=runs.create(actor,project,new AgentRuns.Request(question),UUID.randomUUID().toString());UUID attempt=UUID.randomUUID();db.update("UPDATE agent_run SET status='RUNNING',attempt_token=? WHERE id=?",bytes(attempt),bytes(r.id()));return Map.entry(r.id(),attempt);}
  AgentSettings ledgerSettings(UUID id,java.math.BigDecimal budget){return new AgentSettings(true,true,AgentProvider.QWEN.endpoint,"never-sent",AgentProvider.QWEN.model,"baseline-ledger-"+id,budget,2000,8000);}
  long balance(AgentSettings s){return db.queryForObject("SELECT reserved_nano_cny FROM agent_provider_budget WHERE authorization_id=?",Long.class,s.authorization);}

  @Test void ordinaryMemberCanAskAndUnrelatedChangesDoNotInvalidateItsAnswer()throws Exception {
    fixture();script.add(q->tool("list_tasks",Map.of("query","登录模块")));script.add(q->answer(q,"登录模块 TODO。","FACT"));
    var created=runs.create(member,project,new AgentRuns.Request("登录模块当前状态"),UUID.randomUUID().toString());pump(Set.of("RUN_AGENT"));
    assertThat(runs.get(member,project,created.id()).status()).isEqualTo("SUCCEEDED");
    tasks.create(actor,project,new TaskRequests.Create("独立事项",null,TaskStatus.TODO,TaskType.TASK,2,null,null),UUID.randomUUID().toString());
    assertThat(runs.get(member,project,created.id()).status()).isEqualTo("SUCCEEDED");
    db.update("UPDATE project SET archived=TRUE,business_revision=business_revision+1 WHERE id=?",bytes(project));
    script.clear();calls.set(0);script.add(q->tool("list_tasks",Map.of("query","登录模块")));script.add(q->answer(q,"登录模块 TODO。","FACT"));
    var archived=runs.create(member,project,new AgentRuns.Request("归档后仍可查状态"),UUID.randomUUID().toString());pump(Set.of("RUN_AGENT"));
    assertThat(runs.get(member,project,archived.id()).status()).isEqualTo("SUCCEEDED");assertThat(runs.get(member,project,created.id()).status()).isEqualTo("SUCCEEDED");
  }
  @TestConfiguration static class IsolatedIndexConfiguration {
    @Bean @Primary FixtureIndex fixtureIndex(){return new FixtureIndex();}
  }
  static class FixtureIndex implements TextIndex {
    volatile TextIndex delegate;
    public void publish(List<TextIndex.Document> docs){delegate.publish(docs);}
    public TextIndex.Candidates search(UUID p,String q,int size){return delegate.search(p,q,size);}
  }
}
