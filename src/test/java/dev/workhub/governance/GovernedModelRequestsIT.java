package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

class GovernedModelRequestsIT {
  HikariDataSource source;JdbcTemplate db;AgentAudit audit;ModelAdmission gate;
  GovernedModelRequests requests;AgentSettings settings;MockWebServer model;UUID run,attempt;String scope;
  long began;AtomicInteger dispatched=new AtomicInteger();
  @BeforeEach void setup()throws Exception {
    began=System.nanoTime();source=SharedBudgetIT.pool();db=new JdbcTemplate(source);Flyway.configure().dataSource(source).load().migrate();
    UUID user=UUID.randomUUID(),workspace=UUID.randomUUID(),project=UUID.randomUUID();run=UUID.randomUUID();attempt=UUID.randomUUID();
    db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'Synthetic governance','unused')",bytes(user),"w2-"+user);
    db.update("INSERT INTO workspace(id,name) VALUES (?,'Synthetic governance')",bytes(workspace));
    db.update("INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Synthetic governance')",bytes(project),bytes(workspace));
    db.update("INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision,status,attempt_token) VALUES (?,?,?,'Synthetic governance','{}',0,'RUNNING',?)",bytes(run),bytes(project),bytes(user),bytes(attempt));
    model=new MockWebServer();model.start();settings=new AgentSettings(true,false,model.url("/chat/completions").toString(),"synthetic","glm-4.7","w2-"+run,BigDecimal.ONE,0,0);
    ReflectionTestUtils.setField(settings,"inputPrice",2L);ReflectionTestUtils.setField(settings,"outputPrice",5L);
    var proxy=new ProxyFactory(new AgentAudit(db,new Json(new ObjectMapper()),settings));proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));audit=(AgentAudit)proxy.getProxy();
    scope="governed-"+run;gate=ModelAdmissionIT.gate(scope,1000,1);ModelAdmissionIT.initialize(gate,scope,1000,1);
    requests=new GovernedModelRequests(gate,audit,settings);
  }
  @AfterEach void cleanup(TestInfo info)throws Exception {
    var facts=new LinkedHashMap<String,Object>();facts.put("test",info.getDisplayName());facts.put("run",run.toString());facts.put("httpRequests",model.getRequestCount());facts.put("dispatched",dispatched.get());facts.put("elapsedMillis",(System.nanoTime()-began)/1_000_000);
    facts.put("calls",db.queryForList("SELECT state,error_code,reserved_nano_usd,estimated_nano_usd FROM agent_model_call WHERE run_id=?",bytes(run)));
    facts.put("runState",db.queryForMap("SELECT model_wait_millis,model_retries FROM agent_run WHERE id=?",bytes(run)));
    facts.put("trace",db.queryForList("SELECT kind,payload FROM agent_trace WHERE run_id=? AND kind NOT IN ('MODEL_INPUT','MODEL_OUTPUT') ORDER BY id",bytes(run)));
    Files.createDirectories(Path.of("target/governed-model"));new ObjectMapper().writeValue(Path.of("target/governed-model",run+".json").toFile(),facts);
    model.close();gate.close();source.close();
  }
  AgentModel.Reply call(long millis){return call(millis,()->{});}
  AgentModel.Reply call(long millis,Runnable authorize){return requests.call(run,attempt,new GlmChatModel(settings),List.of(new UserMessage("synthetic")),List.of(),2000,System.nanoTime()+millis*1_000_000,authorize,dispatched::incrementAndGet);}
  void ok(){model.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"glm-4.7\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"));}
  void error(int status){model.enqueue(new MockResponse().setResponseCode(status));}
  void cooldown(long millis){ModelAdmissionIT.redis(gate).opsForHash().put(ModelAdmissionIT.key(scope),"cooldown",""+(System.currentTimeMillis()+millis));}
  void noCall(){
    assertThat(model.getRequestCount()).isZero();
    // Preparation can reserve before quota waiting; every unsent row must be finalized at zero charge.
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=? AND (state<>'COMPLETE' OR estimated_nano_usd<>0 OR error_code IS NULL)",Integer.class,bytes(run))).isZero();
    var occupied=db.queryForList("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,settings.authorization);assertThat(occupied).allMatch(v->v==0);
  }
  @Test void everyPhysicalRetryReservesAndRunAllowsOnlyTwoExtras(){
    for(int i=0;i<2;i++){error(503);ok();call(10000);}
    error(503);assertThatThrownBy(()->call(10000)).hasFieldOrPropertyWithValue("code","GLM_HTTP_503");
    assertThat(model.getRequestCount()).isEqualTo(5);assertThat(audit.calls(run)).isEqualTo(5);
    assertThat(db.queryForObject("SELECT model_retries FROM agent_run WHERE id=?",Integer.class,bytes(run))).isEqualTo(2);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=? AND state='UNKNOWN'",Integer.class,bytes(run))).isEqualTo(3);
  }
  @Test void logicalRequestRetriesOnceAndLong429HintCannotExceedDeadline(){
    error(502);error(504);assertThatThrownBy(()->call(10000)).hasFieldOrPropertyWithValue("code","GLM_HTTP_504");assertThat(model.getRequestCount()).isEqualTo(2);
    model.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","120"));
    assertThatThrownBy(()->call(10000)).hasFieldOrPropertyWithValue("code","AGENT_TIMEOUT");assertThat(model.getRequestCount()).isEqualTo(3);
    assertThat(gate.acquire().reason()).isEqualTo("COOLDOWN");
  }
  @Test void readTimeoutRemainsUnknownWithoutRetry(){
    model.enqueue(new MockResponse().setBodyDelay(2,TimeUnit.SECONDS).setBody("{}"));
    assertThatThrownBy(()->call(400)).hasFieldOrPropertyWithValue("code","GLM_TIMEOUT");
    assertThat(model.getRequestCount()).isEqualTo(1);assertThat(audit.calls(run)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(run))).isEqualTo("UNKNOWN");
  }
  @RepeatedTest(3) void waitingReleasesDatabaseConnectionsThenSends()throws Exception {
    ok();var executor=Executors.newSingleThreadExecutor();var checks=new AtomicInteger();try{
      // Begin cooldown at send-readiness, so cold client setup cannot consume the test's waiting window.
      var future=executor.submit(()->call(5000,()->{if(checks.incrementAndGet()==2)cooldown(800);}));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_WAIT'",Integer.class,bytes(run))==0){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(10);}
      Thread.sleep(40);assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();assertThat(future.get(5,TimeUnit.SECONDS).inputTokens()).isEqualTo(10);
      assertThat(model.getRequestCount()).isEqualTo(1);assertThat(audit.calls(run)).isEqualTo(1);
    }finally{executor.shutdownNow();}
  }
  @RepeatedTest(3) void deadlineEndsWaitAndReleasesPreparedHold(){cooldown(30000);assertThatThrownBy(()->call(200)).hasFieldOrPropertyWithValue("code","AGENT_TIMEOUT");noCall();}
  @RepeatedTest(3) void interruptionEndsWaitAndReleasesHold()throws Exception {
    cooldown(30000);var executor=Executors.newSingleThreadExecutor();var seen=new CompletableFuture<String>();
    try{var future=executor.submit(()->{try{call(10000);seen.complete("unexpected");}catch(ApiException e){seen.complete(e.code);}});
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_WAIT'",Integer.class,bytes(run))==0){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(10);}
      future.cancel(true);assertThat(seen.get(3,TimeUnit.SECONDS)).isEqualTo("AGENT_TIMEOUT");noCall();
    }finally{executor.shutdownNow();}
  }
  @Test void supersededAttemptAndRevokedAuthorizationCannotSendAfterWait(){
    cooldown(300);var checks=new AtomicInteger();assertThatThrownBy(()->call(3000,()->{if(checks.incrementAndGet()==3)db.update("UPDATE agent_run SET attempt_token=? WHERE id=?",bytes(UUID.randomUUID()),bytes(run));})).hasFieldOrPropertyWithValue("code","AGENT_ATTEMPT_SUPERSEDED");noCall();
  }
  @Test void deadlineAfterRetryAndTotalCallCeilingAreStillEnforced(){
    error(503);assertThatThrownBy(()->call(800)).hasFieldOrPropertyWithValue("code","AGENT_TIMEOUT");assertThat(model.getRequestCount()).isEqualTo(1);
    for(int i=1;i<20;i++)audit.reserve(run,attempt,2000,4096);
    ok();assertThatThrownBy(()->call(3000)).hasFieldOrPropertyWithValue("code","AGENT_ROUND_BUDGET");assertThat(model.getRequestCount()).isEqualTo(1);
  }
  @Test void cumulativeWaitAllowanceSurvivesAttemptReplacement(){
    assertThat(audit.reserveWait(run,attempt,15000)).isEqualTo(15000);assertThat(audit.reserveWait(run,attempt,15000)).isEqualTo(15000);
    UUID old=attempt;attempt=UUID.randomUUID();db.update("UPDATE agent_run SET attempt_token=? WHERE id=?",bytes(attempt),bytes(run));audit.finishWait(run,old,15000,0);
    assertThatThrownBy(()->call(1000)).hasFieldOrPropertyWithValue("code","MODEL_WAIT_BUDGET");noCall();
  }
  @RepeatedTest(3) void singleAdmissionWaitStopsAtFifteenSeconds(){
    cooldown(30000);long start=System.nanoTime();assertThatThrownBy(()->call(25000)).hasFieldOrPropertyWithValue("code","MODEL_WAIT_TIMEOUT");
    assertThat((System.nanoTime()-start)/1_000_000).isBetween(15000L,16000L);noCall();
  }
  @Test void missingRedisStateReleasesHoldAndHasNoHttp(){ModelAdmissionIT.redis(gate).delete(ModelAdmissionIT.key(scope));assertThatThrownBy(()->call(3000)).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_MISSING");noCall();}
  @RepeatedTest(3) void lostRedisResponseNeverSpendsOrSendsEvenWhenRedisGranted()throws Exception {
    try(var proxy=new RedisResponseLossProxy(ModelAdmissionIT.port())){
      var uncertain=new ModelAdmission(true,scope,1000,1,"127.0.0.1",proxy.port(),"");
      try{
        try(var connection=ModelAdmissionIT.redis(uncertain).getConnectionFactory().getConnection()){connection.ping();}
        requests=new GovernedModelRequests(uncertain,audit,settings);long start=System.nanoTime();
        assertThatThrownBy(()->call(5000)).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_UNAVAILABLE");
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(2000);
        assertThat(proxy.dropped).isTrue();
        assertThat(ModelAdmissionIT.redis(gate).keys(ModelAdmissionIT.key(scope)+":permit:*")).hasSize(1);
        noCall();
      }finally{uncertain.close();}
    }
  }

  @Test void slowClientSetupAcquiresOnlyAfterPreparation(){
    ok();var setups=new AtomicInteger();
    var slow=new GlmChatModel(settings){@Override public Reply call(java.util.List<org.springframework.ai.chat.messages.Message> messages,java.util.List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> tools,long remaining,Runnable beforeSend){
      if(setups.incrementAndGet()==1)try{Thread.sleep(600);}catch(InterruptedException e){throw new RuntimeException(e);}
      return super.call(messages,tools,remaining,beforeSend);
    }};
    requests.call(run,attempt,slow,List.of(new UserMessage("synthetic")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),()->{},dispatched::incrementAndGet);
    assertThat(setups.get()).isEqualTo(1);assertThat(model.getRequestCount()).isEqualTo(1);assertThat(dispatched.get()).isEqualTo(1);assertThat(audit.calls(run)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_PERMIT_EXPIRED'",Integer.class,bytes(run))).isZero();
  }

}
