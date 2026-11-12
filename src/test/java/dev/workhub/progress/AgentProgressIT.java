package dev.workhub.progress;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "workhub.messaging.enabled=false","workhub.retrieval.enabled=false","workhub.embedding.enabled=false",
  "workhub.agent.legacy-mode=true","workhub.agent.enabled=true","workhub.github.enabled=false","workhub.bootstrap.login=",
  "workhub.agent.endpoint=http://127.0.0.1:9/api/paas/v4/chat/completions","workhub.agent.api-key=synthetic-no-network",
  "workhub.agent.model=glm-4.7","workhub.agent.paid-approved=false","workhub.agent.budget-usd=0",
  "server.servlet.session.cookie.secure=false","debug=false"})
@Import(AgentProgressIT.FixtureConfiguration.class)
class AgentProgressIT {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","25306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("workhub.agent.authorization-id",()->"w3-free-"+UUID.randomUUID());
  }
  @Autowired JdbcTemplate db;@Autowired PasswordEncoder passwords;@Autowired ProjectService projects;
  @Autowired AgentRuns runs;@Autowired AgentProgress progress;@Autowired Fixture fixture;@LocalServerPort int port;
  @Autowired dev.workhub.work.TaskService tasks;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired AgentProgressStreams streams;@Autowired javax.sql.DataSource dataSource;
  ActorContext actor,member;UUID workspace,project,run;String login;
  @BeforeEach void seed() {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_test");
    actor=new ActorContext(UUID.randomUUID());member=new ActorContext(UUID.randomUUID());workspace=UUID.randomUUID();login="w3-"+actor.userId();
    for(var a:List.of(actor,member))db.update("INSERT INTO app_user(id,login,display_name,password_hash,email_verified) VALUES (?,?,?,?,true)",bytes(a.userId()),"w3-"+a.userId(),"SSE 合成成员",passwords.encode("SyntheticW3!"));
    db.update("INSERT INTO workspace(id,name) VALUES (?,?)",bytes(workspace),"SSE isolated fixture");
    for(var a:List.of(actor,member))db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(a.userId()));
    project=projects.create(actor,workspace,"SSE 恢复验证",UUID.randomUUID().toString()).id();
    projects.member(actor,project,member.userId(),ProjectRole.MEMBER,true,projects.get(actor,project).businessRevision());
    run=runs.create(actor,project,new AgentRuns.Request("合成 QA，不调用模型"),UUID.randomUUID().toString()).id();
    fixture.project=project;fixture.actor=actor.userId();
  }
  @AfterEach void cleanup() {
    db.update("UPDATE app_user SET active=false WHERE id IN (?,?)",bytes(actor.userId()),bytes(member.userId()));
    db.update("UPDATE agent_run SET status='FAILED',error_code='FIXTURE_DONE' WHERE project_id=? AND status IN ('QUEUED','RUNNING')",bytes(project));
  }
  @RepeatedTest(3) void snapshotAuthorizationAndPayloadAllowlist() {
    var value=progress.snapshot(actor,project,run);assertThat(value.status()).isEqualTo("QUEUED");
    assertThatThrownBy(()->progress.snapshot(member,project,run)).hasFieldOrPropertyWithValue("status",404);
    assertThatThrownBy(()->progress.snapshot(actor,UUID.randomUUID(),run)).hasFieldOrPropertyWithValue("status",403);
    UUID otherWorkspace=UUID.randomUUID();
    db.update("INSERT INTO workspace(id,name) VALUES (?,?)",bytes(otherWorkspace),"Other fixture workspace");
    db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(otherWorkspace),bytes(member.userId()));
    var other=projects.create(member,otherWorkspace,"Other project",UUID.randomUUID().toString());
    assertThatThrownBy(()->progress.snapshot(actor,other.id(),run)).hasFieldOrPropertyWithValue("status",403);
    var task=tasks.create(member,project,new dev.workhub.work.TaskRequests.Create("SSE 分诊权限","合成任务",null,null,null,null,null),UUID.randomUUID().toString());
    db.update("UPDATE agent_run SET triage_task_id=? WHERE id=?",bytes(task.id()),bytes(run));
    assertThatThrownBy(()->progress.snapshot(member,project,run)).hasFieldOrPropertyWithValue("status",403);
    assertThat(progress.snapshot(actor,project,run).taskId()).isEqualTo(task.id());
    db.update("UPDATE agent_run SET triage_task_id=NULL WHERE id=?",bytes(run));
    fixture.stage(project,run,Map.of("kind","MODEL_WAIT","reason","COOLDOWN"));
    value=progress.snapshot(actor,project,run);assertThat(value.stage()).isEqualTo("WAITING");assertThat(value.reason()).isEqualTo("COOLDOWN");
    db.update("UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",bytes(project),bytes(actor.userId()));
    assertThatThrownBy(()->progress.snapshot(actor,project,run)).hasFieldOrPropertyWithValue("status",403);
  }
  @RepeatedTest(3) void actualSnapshotTransactionEndsBeforeServletWrite() throws Exception {
    var request=org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
    var response=org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
    var async=org.mockito.Mockito.mock(jakarta.servlet.AsyncContext.class);
    var output=org.mockito.Mockito.mock(jakarta.servlet.ServletOutputStream.class);
    var sent=new java.util.concurrent.CompletableFuture<Boolean>();
    String sessionId=UUID.randomUUID().toString();
    db.update("INSERT INTO SPRING_SESSION(PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,PRINCIPAL_NAME) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),sessionId,System.currentTimeMillis(),System.currentTimeMillis(),600,System.currentTimeMillis()+600000,login);
    var session=org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class);
    org.mockito.Mockito.when(session.getId()).thenReturn(sessionId);
    org.mockito.Mockito.when(request.getSession(false)).thenReturn(session);
    org.mockito.Mockito.when(request.startAsync()).thenReturn(async);
    org.mockito.Mockito.when(response.getOutputStream()).thenReturn(output);
    org.mockito.Mockito.when(output.isReady()).thenReturn(true);
    org.mockito.Mockito.doAnswer(call->{sent.complete(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&!org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource));return null;}).when(output).write(org.mockito.ArgumentMatchers.any(byte[].class));
    streams.open(actor,project,run,request,response);
    assertThat(sent.get(3,TimeUnit.SECONDS)).isTrue();
    // A removed JDBC session closes an existing connection even while membership stays valid.
    db.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID=?",sessionId);
    org.mockito.Mockito.verify(async,org.mockito.Mockito.timeout(3000)).complete();
  }
  @Test void supersededAttemptCannotDescribeCurrentStage() {
    fixture.stage(project,run,Map.of("kind","MODEL_WAIT","reason","COOLDOWN"));
    db.update("UPDATE agent_run SET attempt_token=? WHERE id=?",bytes(UUID.randomUUID()),bytes(run));
    var value=progress.snapshot(actor,project,run);
    assertThat(value.stage()).isEqualTo("RUNNING");assertThat(value.reason()).isNull();assertThat(value.sequence()).isPositive();
  }
  @Test void uncommittedTraceIsNeverExposed() throws Exception {
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
        db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,'MODEL_INPUT',JSON_OBJECT('private','hidden'))",bytes(run),bytes(UUID.randomUUID()));
        try{assertThat(pool.submit(()->progress.snapshot(actor,project,run)).get(3,TimeUnit.SECONDS).sequence()).isZero();}
        catch(Exception e){throw new RuntimeException(e);}
      });
      assertThat(progress.snapshot(actor,project,run).sequence()).isPositive();
    }finally{pool.shutdownNow();}
  }
  @RepeatedTest(3) void browserRecoveryAndCommittedProgress(RepetitionInfo repeat) throws Exception {
    Path dir=Path.of("target/w3-progress",UUID.randomUUID().toString());Files.createDirectories(dir);
    var process=new ProcessBuilder("node","scripts/verify-w3-progress.cjs",String.valueOf(port),login,project.toString(),run.toString(),dir.toString(),String.valueOf(repeat.getCurrentRepetition()),member.userId().toString()).redirectErrorStream(true).redirectOutput(dir.resolve("browser.log").toFile()).start();
    try{assertThat(process.waitFor(180,TimeUnit.SECONDS)).as("browser completed; see %s",dir).isTrue();assertThat(process.exitValue()).as(Files.readString(dir.resolve("browser.log"))).isZero();}
    finally{if(process.isAlive())process.destroyForcibly();}
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE project_id=?",Integer.class,bytes(project))).isEqualTo(2);
  }
  @TestConfiguration static class FixtureConfiguration { @Bean Fixture fixture(JdbcTemplate db){return new Fixture(db);} }
  // Test-only synthetic state driver: no model, no business mutations outside this fixture project.
  @RestController static class Fixture {
    final JdbcTemplate db;volatile UUID project,actor;Fixture(JdbcTemplate db){this.db=db;}
    void check(UUID p){if(!p.equals(project))throw ApiException.forbidden();}
    @PostMapping("/projects/{p}/w3-fixture/{id}/stage")
    Map<String,Object> stage(@PathVariable UUID p,@PathVariable UUID id,@RequestBody Map<String,String> body) {
      check(p);String status=body.getOrDefault("status","RUNNING");
      if(!Set.of("RUNNING","FAILED","INCOMPLETE","SUCCEEDED").contains(status))throw ApiException.invalid("fixture status");
      db.update("UPDATE agent_run SET status=?,error_code=? WHERE id=? AND project_id=?",status,body.get("errorCode"),bytes(id),bytes(p));
      String kind=body.getOrDefault("kind","MODEL_WAIT"),reason=body.getOrDefault("reason","RATE");
      db.update("INSERT INTO agent_trace(run_id,attempt_token,kind,payload) VALUES (?,?,?,JSON_OBJECT('reason',?,'private','DO_NOT_EXPOSE_MODEL_BODY'))",bytes(id),bytes(UUID.randomUUID()),kind,reason);
      long committedAt=System.currentTimeMillis();
      long seq=db.queryForObject("SELECT MAX(id) FROM agent_trace WHERE run_id=?",Long.class,bytes(id));
      return Map.of("sequence",seq,"committedAt",committedAt);
    }
    @PostMapping("/projects/{p}/w3-fixture/revoke") Map<String,Object> revoke(@PathVariable UUID p) {
      check(p);db.update("UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",bytes(p),bytes(actor));return Map.of("at",System.currentTimeMillis());
    }
  }
}
