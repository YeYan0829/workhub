package dev.workhub.phase5b;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.workhub.agent.*;
import dev.workhub.agent.TriageProposals.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.integration.github.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real GitHub adapter -> mirror -> read-only analysis -> explicit local acceptance.
 * Both external services are fixtures; these checks are not model-quality evidence. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "workhub.messaging.enabled=false","workhub.retrieval.enabled=false","workhub.embedding.enabled=false",
  "workhub.agent.enabled=true","workhub.bootstrap.login=",
  "workhub.github.fake-token=fixture","workhub.github.dispatch-enabled=true","workhub.github.webhook-secret=test-secret",
  "workhub.agent.endpoint=http://127.0.0.1:9/api/paas/v4/chat/completions",
  "workhub.agent.api-key=synthetic-no-network","workhub.agent.model=glm-4.7",
  "workhub.agent.paid-approved=false","workhub.agent.budget-usd=0",
  "server.servlet.session.cookie.secure=false","debug=false"})
@Import(TriageProposalIT.ModelConfiguration.class)
class GitHubTriageIT {
  static final MockWebServer github = new MockWebServer();
  static final AtomicInteger patches = new AtomicInteger();
  static volatile ObjectNode issue;
  static volatile long repository;
  static final Map<Integer,ObjectNode> remoteIssues=new java.util.concurrent.ConcurrentSkipListMap<>();
  static {
    try { github.start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    github.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest r) {
        String path=Objects.requireNonNull(r.getPath());
        if(!"GET".equals(r.getMethod())) { patches.incrementAndGet();return response(500,"{}"); }
        if(path.equals("/repos/fixture/appointments"))return response(200,"{\"id\":"+repository+"}");
        if(path.contains("/comments"))return response(200,"[]");
        if(path.contains("?state=")) {
          int page=Integer.parseInt(Objects.requireNonNull(r.getRequestUrl()).queryParameter("page"));
          return response(200,remoteIssues.values().stream().skip((page-1)*100L).limit(100).map(Object::toString).collect(java.util.stream.Collectors.joining(",","[","]")));
        }
        if(path.equals("/repos/fixture/appointments/issues/1"))return response(200,issue.toString());
        var found=remoteIssues.values().stream().filter(v->path.equals("/repos/fixture/appointments/issues/"+v.path("number").asInt())).findFirst();
        return found.map(v->response(200,v.toString())).orElseGet(()->response(404,"{}"));
      }
    });
  }
  static MockResponse response(int code,String body) {
    return new MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body);
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","13306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("workhub.github.api-base",()->github.url("/").toString());
    p.add("workhub.agent.authorization-id",()->"github-triage-fixture-"+UUID.randomUUID());
  }
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper mapper;
  @Autowired ProjectService projects;
  @Autowired TaskService tasks;
  @Autowired ContentService content;
  @Autowired GitHubService integration;
  @Autowired GitHubStore store;
  @Autowired AgentRuns runs;
  @Autowired AgentTools tools;
  @Autowired AutomaticTriage automatic;
  @Autowired TriageProposals proposals;
  @Autowired WorkJobExecutor executor;
  @Autowired OutboxRepository outbox;
  @Autowired PlatformTransactionManager transactions;
  @Autowired PasswordEncoder passwords;
  @Autowired TriageProposalIT.ScriptModel model;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  ActorContext actor,member;
  UUID workspace,project,connection,resource,document;
  Task task;
  String login;

  @BeforeEach void seed(TestInfo test) throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_test");
    patches.set(0);remoteIssues.clear();repository=Math.abs(UUID.randomUUID().getMostSignificantBits());
    actor=new ActorContext(UUID.randomUUID());member=new ActorContext(UUID.randomUUID());
    workspace=UUID.randomUUID();login="p5b-"+actor.userId();
    var fixture=mapper.readTree(Files.readString(Path.of("src/test/resources/phase5b/triage.json")));
    for(var a:List.of(actor,member))db.update("INSERT INTO app_user(id,login,display_name,password_hash,email_verified) VALUES (?,?,?,?,true)",
        bytes(a.userId()),"p5b-"+a.userId(),a==actor?"维护者":"成员",passwords.encode("Synthetic5b!"));
    db.update("INSERT INTO workspace(id,name) VALUES (?,?)",bytes(workspace),"GitHub triage fixture");
    for(var a:List.of(actor,member))db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(a.userId()));
    project=projects.create(actor,workspace,fixture.path("project").asText(),key()).id();
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,true,projects.get(actor,project).businessRevision());
    document=content.createDocument(actor,project,ContentRepository.DocumentKind.DOCUMENT,
        fixture.at("/document/title").asText(),fixture.at("/document/body").asText(),key()).id();
    issue=mapper.createObjectNode().put("id",101).put("number",1)
        .put("title",fixture.at("/task/title").asText()).put("body",fixture.at("/task/description").asText())
        .put("state","open").putNull("state_reason").put("created_at","2026-09-01T00:00:00Z")
        .put("updated_at","2026-09-01T00:00:00Z").put("html_url","https://github.com/fixture/appointments/issues/1");
    db.update("INSERT INTO github_installation_grant(installation_id,workspace_id) VALUES (?,?)",repository,bytes(workspace));
    db.update("INSERT INTO github_repository_grant(repository_id,installation_id,repository_name) VALUES (?,?,'fixture/appointments')",repository,repository);
    remoteIssues.put(1,issue);
    if(test.getTestMethod().orElseThrow().getName().equals("hundredBackfillTwentyNewAndTwentyDuplicateDeliveriesCreateExactlyTwentyRuns")) {
      enableAutomatic();
      for(int i=2;i<=100;i++)remoteIssues.put(i,newIssue(i,"2026-09-01T00:00:00Z"));
    }
    if(test.getTestMethod().orElseThrow().getName().equals("initialBackfillIsExcludedEvenWhenSourceWasCreatedAfterEnable")) {
      enableAutomatic();issue.put("created_at",java.time.Instant.now().toString());
    }
    connection=integration.connect(actor,project,new GitHubService.Connect(repository,repository,"fixture/appointments"),key());
    pumpGitHub();
    var row=db.queryForMap("SELECT resource_id,task_id FROM github_issue WHERE connection_id=? AND issue_number=1",bytes(connection));
    resource=uuid((byte[])row.get("resource_id"));task=tasks.get(actor,project,uuid((byte[])row.get("task_id")));
    assertThat(task.origin()).isEqualTo(TaskOrigin.GITHUB);assertThat(task.status()).isEqualTo(TaskStatus.TRIAGE);
    model.task=task.id();model.document=document;model.turn=0;model.insufficient=false;model.beforeAnswer=null;
  }
  @AfterEach void retireFixture(TestInfo test) throws Exception {
    if(project!=null) {
      var result=new LinkedHashMap<String,Object>();
      result.put("scenario",test.getTestMethod().orElseThrow().getName());
      result.put("projectId",project);result.put("taskId",task==null?null:task.id());
      result.put("mode","local GitHub HTTP fixture and scripted model; no quality score");
      result.put("remotePatchCount",patches.get());
      result.put("tasks",db.queryForList("SELECT origin,status,revision,triage_required FROM task WHERE project_id=?",bytes(project)));
      result.put("runs",db.queryForList("SELECT BIN_TO_UUID(id) run_id,status,error_code FROM agent_run WHERE project_id=?",bytes(project)));
      result.put("proposalExecutionCount",db.queryForObject("SELECT COUNT(*) FROM agent_proposal_execution e JOIN agent_proposal p ON p.id=e.proposal_id WHERE p.project_id=?",Integer.class,bytes(project)));
      result.put("acceptedActivityCount",db.queryForObject("SELECT COUNT(*) FROM activity WHERE project_id=? AND kind='TASK_ACCEPTED'",Integer.class,bytes(project)));
      var dir=Path.of("target/github-triage");Files.createDirectories(dir);
      Files.writeString(dir.resolve(test.getTestMethod().orElseThrow().getName()+"-"+project+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    if(project!=null)db.update("UPDATE project_member SET active=false WHERE project_id=?",bytes(project));
    if(workspace!=null)db.update("UPDATE workspace_member SET active=false WHERE workspace_id=?",bytes(workspace));
  }
  @AfterAll static void closeProvider() throws Exception { github.shutdown(); }
  String key(){return UUID.randomUUID().toString();}
  void pump(String handler) {
    var rows=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler=? AND j.status='WAITING' AND j.next_attempt_at<=UTC_TIMESTAMP(6)",bytes(project),handler);
    for(var row:rows){var e=outbox.find(uuid((byte[])row.get("id")),false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(StandardCharsets.UTF_8));}
  }
  void pumpGitHub() {
    for(int i=0;i<12;i++) {
      int waiting=db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='GITHUB_WORK' AND status='WAITING'",Integer.class,bytes(project));
      if(waiting==0)return;
      pump("GITHUB_WORK");
    }
    fail("GitHub fixture did not drain within 12 passes");
  }
  void refreshRemote() {
    var tx=new TransactionTemplate(transactions);
    tx.executeWithoutResult(s->{store.lockProject(project);store.refresh(resource);});pumpGitHub();
  }
  UUID analyze() {
    var r=runs.createTriage(actor,project,task.id(),new AgentRuns.TriageRequest("核对取消名额约定",TriageStrategy.WORKFLOW),key());
    pump("RUN_AGENT");var result=runs.get(actor,project,r.id());
    assertThat(result.status()).as(result.errorCode()).isEqualTo("SUCCEEDED");return r.id();
  }
  View ready() {
    var v=proposals.create(actor,project,analyze());
    v=proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),new Fields(TaskType.BUG,1,member.userId(),null,"核对约定后接纳，本地安排不修改GitHub")));
    return proposals.preview(actor,project,v.id(),new Edit(v.version(),v.hash(),null));
  }
  Approval approval(View v){return new Approval(v.version(),v.hash(),v.previewToken());}

  void enableAutomatic() {
    automatic.update(actor,project,new AutomaticTriage.Update(true,TriageStrategy.WORKFLOW,automatic.get(actor,project).revision()));
    // Controlled test clock boundary, independent of GitHub's second-precision timestamps.
    db.update("UPDATE project_triage_settings SET enabled_since=UTC_TIMESTAMP(6)-INTERVAL 5 SECOND WHERE project_id=?",bytes(project));
  }
  ObjectNode newIssue(int number,String created) {
    return issue.deepCopy().put("id",100+number).put("number",number).put("created_at",created)
        .put("updated_at",java.time.Instant.now().toString()).put("html_url","https://github.com/fixture/appointments/issues/"+number);
  }
  GitHubService.Accepted webhook(ObjectNode source,String action,String delivery) throws Exception {
    byte[] raw=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",repository),"repository",Map.of("id",repository),"action",action,"issue",source));
    var mac=javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    return integration.webhook(delivery,"issues","sha256="+HexFormat.of().formatHex(mac.doFinal(raw)),raw);
  }
  long automaticCount() {
    return db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=? AND automatic_task_id IS NOT NULL",Long.class,bytes(project));
  }
  Task imported(int number) {
    return tasks.get(actor,project,uuid(db.queryForObject("SELECT task_id FROM github_issue WHERE connection_id=? AND issue_number=?",byte[].class,bytes(connection),number)));
  }
  @Test void hundredBackfillTwentyNewAndTwentyDuplicateDeliveriesCreateExactlyTwentyRuns() throws Exception {
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task WHERE project_id=?",Long.class,bytes(project))).isEqualTo(100);
    assertThat(automaticCount()).isZero();
    for(int n=101;n<=120;n++) {
      var source=newIssue(n,java.time.Instant.now().toString());remoteIssues.put(n,source);String delivery=key();
      assertThat(webhook(source,"opened",delivery).status()).isEqualTo("ACCEPTED");
      if(n<=104)for(int duplicate=0;duplicate<5;duplicate++)assertThat(webhook(source,"opened",delivery).status()).isEqualTo("DUPLICATE");
    }
    pumpGitHub();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task WHERE project_id=?",Long.class,bytes(project))).isEqualTo(120);
    assertThat(automaticCount()).isEqualTo(20);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run r JOIN github_issue i ON i.task_id=r.automatic_task_id WHERE r.project_id=? AND i.discovery_mode='BACKFILL'",Long.class,bytes(project))).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='RUN_AGENT'",Long.class,bytes(project))).isEqualTo(20);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.handler='RUN_AGENT'",Long.class,bytes(project))).isEqualTo(20);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace t JOIN agent_run r ON r.id=t.run_id WHERE r.project_id=? AND t.kind='AUTOMATIC_TRIGGER'",Long.class,bytes(project))).isEqualTo(20);
    // Reconciliation and distinct redeliveries of the same issue cannot create another first run.
    integration.reconcile(actor,project,connection);pumpGitHub();
    webhook(remoteIssues.get(101),"opened",key());pumpGitHub();assertThat(automaticCount()).isEqualTo(20);
    assertThat(model.turn).isZero();assertThat(patches.get()).isZero();
  }
  @Test void initialBackfillIsExcludedEvenWhenSourceWasCreatedAfterEnable() {
    assertThat(automatic.get(actor,project).enabled()).isTrue();assertThat(automaticCount()).isZero();
    refreshRemote();assertThat(automaticCount()).isZero();
  }
  @Test void recentFirstImportCanBeAnalyzedAndAcceptedLocally() throws Exception {
    enableAutomatic();var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);
    webhook(source,"opened",key());pumpGitHub();task=imported(2);model.task=task.id();
    var id=uuid(db.queryForObject("SELECT id FROM agent_run WHERE automatic_task_id=?",byte[].class,bytes(task.id())));
    pump("RUN_AGENT");assertThat(runs.get(actor,project,id).status()).isEqualTo("SUCCEEDED");
    var v=proposals.create(actor,project,id);
    v=proposals.edit(actor,project,v.id(),new Edit(v.version(),v.hash(),new Fields(TaskType.BUG,1,member.userId(),null,"维护者核实后安排")));
    v=proposals.preview(actor,project,v.id(),new Edit(v.version(),v.hash(),null));
    proposals.approve(actor,project,v.id(),approval(v));
    assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.TODO);
    assertThat(model.turn).isEqualTo(1);assertThat(patches.get()).isZero();
  }
  @Test void reconciliationFindsRecentMissedIssueButSkipsOldOrUnprovenSources() {
    enableAutomatic();remoteIssues.put(2,newIssue(2,java.time.Instant.now().toString()));
    remoteIssues.put(3,newIssue(3,"2026-09-01T00:00:00Z"));
    remoteIssues.put(4,newIssue(4,"invalid"));
    remoteIssues.put(5,newIssue(5,java.time.Instant.now().plusSeconds(60).toString()));
    integration.reconcile(actor,project,connection);pumpGitHub();
    assertThat(automaticCount()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE automatic_task_id=?",Integer.class,bytes(imported(2).id()))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task WHERE project_id=?",Long.class,bytes(project))).isEqualTo(5);
  }
  @Test void editsReopensClosedAndMissingDatesDoNotStartAutomaticAnalysis() throws Exception {
    enableAutomatic();
    for(int n=2;n<=5;n++) {
      var source=newIssue(n,java.time.Instant.now().toString());
      if(n==4)source.put("state","closed").put("state_reason","completed");
      if(n==5)source.remove("created_at");
      remoteIssues.put(n,source);webhook(source,n==2?"edited":n==3?"reopened":"opened",key());
    }
    pumpGitHub();assertThat(automaticCount()).isZero();
    integration.reconcile(actor,project,connection);pumpGitHub();assertThat(automaticCount()).isZero();
  }
  @Test void projectGrantSurvivesEnablerDepartureAndKeepsAuditIdentity() throws Exception {
    projects.member(actor,project,member.userId(),ProjectRole.MAINTAINER,true,projects.get(actor,project).businessRevision());
    automatic.update(member,project,new AutomaticTriage.Update(true,TriageStrategy.WORKFLOW,0));
    db.update("UPDATE project_triage_settings SET enabled_since=UTC_TIMESTAMP(6)-INTERVAL 5 SECOND WHERE project_id=?",bytes(project));
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,false,projects.get(actor,project).businessRevision());
    var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);
    webhook(source,"opened",key());pumpGitHub();task=imported(2);model.task=task.id();
    assertThat(db.queryForObject("SELECT actor_id FROM agent_run WHERE automatic_task_id=?",byte[].class,bytes(task.id()))).isEqualTo(bytes(member.userId()));
    pump("RUN_AGENT");
    assertThat(db.queryForObject("SELECT status FROM agent_run WHERE automatic_task_id=?",String.class,bytes(task.id()))).isEqualTo("SUCCEEDED");
  }
  @Test void webhookTransactionRollbackLeavesNoTaskRunOrDelivery() throws Exception {
    enableAutomatic();var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);String delivery=key();
    new TransactionTemplate(transactions).executeWithoutResult(s->{
      try { webhook(source,"opened",delivery); } catch(Exception e) { throw new RuntimeException(e); }
      s.setRollbackOnly();
    });
    pumpGitHub();assertThat(automaticCount()).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_inbox WHERE connection_id=? AND delivery_id=?",Long.class,bytes(connection),delivery)).isZero();
    webhook(source,"opened",delivery);pumpGitHub();assertThat(automaticCount()).isEqualTo(1);
  }
  @RepeatedTest(3) void newIssueAfterDisableStillImportsButNeverCallsModel() throws Exception {
    enableAutomatic();automatic.update(actor,project,new AutomaticTriage.Update(false,TriageStrategy.WORKFLOW,1));
    var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);
    webhook(source,"opened",key());pumpGitHub();pump("RUN_AGENT");
    assertThat(imported(2).status()).isEqualTo(TaskStatus.TRIAGE);
    assertThat(automaticCount()).isZero();assertThat(model.turn).isZero();
  }
  @Test void registrationFailureRollsBackMirrorAndRunAndRecoversSameObligation() throws Exception {
    enableAutomatic();var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);
    webhook(source,"opened",key());pump("GITHUB_WORK"); // route is durable; GET/apply is next
    String trigger="fail_triage_"+project.toString().replace("-","");
    db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON agent_trace FOR EACH ROW BEGIN IF NEW.kind='AUTOMATIC_TRIGGER' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic registration failure'; END IF; END");
    try {
      assertThatThrownBy(()->pump("GITHUB_WORK")).isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(automaticCount()).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM task WHERE project_id=?",Long.class,bytes(project))).isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='RUN_AGENT'",Long.class,bytes(project))).isZero();
    } finally {db.execute("DROP TRIGGER "+trigger);}
    // Simulate lease expiry, not a process-kill test. Redeliver the original obligation.
    db.update("UPDATE work_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE project_id=? AND status='RUNNING'",bytes(project));
    db.update("UPDATE external_resource SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE project_id=?",bytes(project));
    db.update("UPDATE github_installation_lane SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE installation_id=?",repository);
    var failed=db.queryForList("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.project_id=? AND j.status='RUNNING'",bytes(project));
    assertThat(failed).hasSize(1);
    var e=outbox.find(uuid((byte[])failed.get(0).get("id")),false);
    executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(StandardCharsets.UTF_8));
    pumpGitHub();assertThat(automaticCount()).isEqualTo(1);
    assertThat(imported(2).status()).isEqualTo(TaskStatus.TRIAGE);
  }

  @Test void importedIssueAnalysisAndTenApprovalRetriesHaveOneLocalEffectAndNoRemoteWrites() {
    var v=ready();assertThat(tasks.get(actor,project,task.id())).isEqualTo(task);
    for(int i=0;i<11;i++)assertThat(proposals.approve(actor,project,v.id(),approval(v)).result().status()).isEqualTo(TaskStatus.TODO);
    var accepted=tasks.get(actor,project,task.id());
    assertThat(accepted.origin()).isEqualTo(TaskOrigin.GITHUB);assertThat(accepted.assigneeId()).isEqualTo(member.userId());
    assertThat(accepted.description()).isEqualTo(task.description());assertThat(issue.path("state").asText()).isEqualTo("open");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_proposal_execution WHERE proposal_id=?",Integer.class,bytes(v.id()))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM activity WHERE project_id=? AND kind='TASK_ACCEPTED'",Integer.class,bytes(project))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM sync_intent WHERE project_id=?",Integer.class,bytes(project))).isZero();
    assertThat(patches.get()).isZero();
  }
  @RepeatedTest(3) void memberCannotStartReadOrApproveMaintainerAnalysis() {
    assertThatThrownBy(()->runs.createTriage(member,project,task.id(),new AgentRuns.Request("分析"),key())).hasFieldOrPropertyWithValue("code","FORBIDDEN");
    var v=ready();UUID run=uuid(db.queryForObject("SELECT run_id FROM agent_proposal WHERE id=?",byte[].class,bytes(v.id())));
    assertThatThrownBy(()->runs.get(member,project,run)).hasFieldOrPropertyWithValue("code","FORBIDDEN");
    assertThatThrownBy(()->proposals.approve(member,project,v.id(),approval(v))).hasFieldOrPropertyWithValue("code","FORBIDDEN");
    assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.TRIAGE);
  }
  @RepeatedTest(3) void remoteEditMakesPreparedProposalStale() {
    var v=ready();issue=issue.deepCopy().put("body","外部复现条件已经改变").put("updated_at","2026-09-02T00:00:00Z");refreshRemote();
    assertThatThrownBy(()->proposals.approve(actor,project,v.id(),approval(v))).hasFieldOrPropertyWithValue("code","PROPOSAL_STALE");
    assertThat(patches.get()).isZero();
  }
  @RepeatedTest(3) void remoteCloseMakesPreparedProposalStaleAndRejectsNewAnalysis() {
    var v=ready();issue=issue.deepCopy().put("state","closed").put("state_reason","completed").put("updated_at","2026-09-02T00:00:00Z");refreshRemote();
    assertThatThrownBy(()->proposals.approve(actor,project,v.id(),approval(v))).hasFieldOrPropertyWithValue("code","PROPOSAL_STALE");
    assertThatThrownBy(()->runs.createTriage(actor,project,task.id(),new AgentRuns.Request("分析"),key())).hasFieldOrPropertyWithValue("code","VALIDATION_ERROR");
    assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.DONE);
  }
  @RepeatedTest(3) void changedCitationCannotBeApproved() {
    var v=ready();var d=content.getDocument(actor,project,document);
    content.editDocument(actor,project,document,d.revision(),d.title(),"约定已撤回，需要重新核查");
    assertThatThrownBy(()->proposals.approve(actor,project,v.id(),approval(v))).hasFieldOrPropertyWithValue("code","AGENT_CONTEXT_CHANGED");
  }
  @Test void lostSubmissionResponseReusesRunAndRepeatedRefreshDoesNotCreateAnalysis() {
    String key=key();var request=new AgentRuns.TriageRequest("核对资料",TriageStrategy.WORKFLOW);
    var first=runs.createTriage(actor,project,task.id(),request,key);
    assertThat(runs.createTriage(actor,project,task.id(),request,key).id()).isEqualTo(first.id());
    refreshRemote();refreshRemote();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=?",Integer.class,bytes(project))).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler='RUN_AGENT'",Integer.class,bytes(project))).isEqualTo(1);
  }
  @Test void fiveConcurrentResultReadsUseFiveConnectionsAndStaleChecksStillCommit() throws Exception {
    var fact=tools.fact(actor,project,"get_task",mapper.valueToTree(Map.of("taskId",task.id())));
    assertThat(tools.invalidSources(actor,project,fact.citations())).isEmpty();
    var id=analyze();parallelReads(id,"SUCCEEDED");
    var d=content.getDocument(actor,project,document);
    content.editDocument(actor,project,document,d.revision(),d.title(),"引用已更新，旧答案不能继续使用");
    parallelReads(id,"STALE");
  }
  void parallelReads(UUID id,String expected) throws Exception {
    var datasource=(com.zaxxer.hikari.HikariDataSource)db.getDataSource();
    assertThat(datasource.getMaximumPoolSize()).isEqualTo(5);
    var gate=new java.util.concurrent.CountDownLatch(5);
    var pool=java.util.concurrent.Executors.newFixedThreadPool(5);
    try {
      var futures=new ArrayList<java.util.concurrent.Future<String>>();
      for(int n=0;n<5;n++)futures.add(pool.submit(()->{
        var tx=new TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(s->{
          db.queryForObject("SELECT 1",Integer.class);gate.countDown();
          try {assertThat(gate.await(5,TimeUnit.SECONDS)).isTrue();}
          catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
          String state=runs.get(actor,project,id).status();
          assertThat(s.isRollbackOnly()).isFalse();return state;
        });
      }));
      for(var f:futures)assertThat(f.get(10,TimeUnit.SECONDS)).isEqualTo(expected);
    } finally {pool.shutdownNow();}
  }
  @Test void browserAnalyzesTheImportedIssueAndRecoversApprovalReceipt() throws Exception {
    browserAcceptance(null,"github-");
  }
  @RepeatedTest(3) void browserOpensAutomaticResultWithoutStartingAnotherRun() throws Exception {
    enableAutomatic();var source=newIssue(2,java.time.Instant.now().toString());remoteIssues.put(2,source);
    webhook(source,"opened",key());pumpGitHub();task=imported(2);model.task=task.id();
    var id=uuid(db.queryForObject("SELECT id FROM agent_run WHERE automatic_task_id=?",byte[].class,bytes(task.id())));
    browserAcceptance(id,"github-automatic-");
    assertThat(automaticCount()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=?",Long.class,bytes(project))).isEqualTo(1);
  }
  void browserAcceptance(UUID automaticRun,String prefix) throws Exception {
    var log=Path.of("target/github-triage-browser.log");
    var args=new ArrayList<>(List.of("node","scripts/verify-phase5b-ui.cjs",String.valueOf(port),login,project.toString(),task.id().toString(),member.userId().toString()));
    if(automaticRun!=null)args.add(automaticRun.toString());
    var process=new ProcessBuilder(args)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
    try {while(process.isAlive()&&System.nanoTime()<end){pump("RUN_AGENT");Thread.sleep(150);}}
    finally {if(process.isAlive())process.destroyForcibly();}
    assertThat(process.waitFor()).as(Files.readString(log)).isZero();
    assertThat(tasks.get(actor,project,task.id()).origin()).isEqualTo(TaskOrigin.GITHUB);
    assertThat(tasks.get(actor,project,task.id()).status()).isEqualTo(TaskStatus.TODO);
    assertThat(patches.get()).isZero();
    for(String file:List.of("phase5b-ui-desktop.png","phase5b-ui.png","phase5b-ui-result.json"))
      Files.copy(Path.of("target",file),Path.of("target",prefix+file),StandardCopyOption.REPLACE_EXISTING);
  }
}
