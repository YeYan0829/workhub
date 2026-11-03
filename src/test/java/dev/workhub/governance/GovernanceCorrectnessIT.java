package dev.workhub.governance;

import static org.assertj.core.api.Assertions.*;
import static dev.workhub.common.Ids.*;
import dev.workhub.agent.*;
import dev.workhub.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

/** Extends the existing MySQL/Redis/HTTP fixture; no new accounting implementation. */
class GovernanceCorrectnessIT extends GovernedModelRequestsIT {
  @Test void confirmedUnsentCancellationReleasesHoldIdempotently() {
    // Adapter setup fails before the required beforeSend callback.
    AgentModel setupFailure=new AgentModel(){
      public Reply call(java.util.List<org.springframework.ai.chat.messages.Message> m,java.util.List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> t,long r){throw new AssertionError();}
      public Reply call(java.util.List<org.springframework.ai.chat.messages.Message> m,java.util.List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> t,long r,Runnable before){throw new ApiException(503,"SETUP_CANCELLED","fixture");}
    };
    assertThatThrownBy(()->requests.call(run,attempt,setupFailure,List.of(new UserMessage("fixture")),List.of(),2000,System.nanoTime()+5_000_000_000L,()->{},()->{})).hasFieldOrPropertyWithValue("code","SETUP_CANCELLED");
    assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,settings.authorization)).isZero();
    assertThat(db.queryForObject("SELECT estimated_nano_usd FROM agent_model_call WHERE run_id=?",Long.class,bytes(run))).isZero();
    UUID call=uuid(db.queryForObject("SELECT id FROM agent_model_call WHERE run_id=?",byte[].class,bytes(run)));
    audit.finishUnsent(call,1,"DUPLICATE");audit.finish(call,10L,5L,1,null);
    assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,settings.authorization)).isZero();
    assertThat(model.getRequestCount()).isZero();
  }
  @Test void disabledAdmissionStillHasSameBoundedRetry() {
    gate.close();gate=new ModelAdmission(false,"",1,1,"127.0.0.1",1,"");requests=new GovernedModelRequests(gate,audit,settings);
    error(503);ok();assertThat(call(10000).inputTokens()).isEqualTo(10);
    assertThat(model.getRequestCount()).isEqualTo(2);
  }
  @Test void rateLimitRetryRespectsHintAndReentersAdmission() {
    model.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(429).setHeader("Retry-After","1"));ok();
    long start=System.nanoTime();assertThat(call(8000).inputTokens()).isEqualTo(10);
    assertThat((System.nanoTime()-start)/1_000_000).isGreaterThanOrEqualTo(1000);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_PERMIT'",Integer.class,bytes(run))).isGreaterThanOrEqualTo(2);
    assertThat(model.getRequestCount()).isEqualTo(2);
    assertThat(db.queryForObject("SELECT model_retries FROM agent_run WHERE id=?",Integer.class,bytes(run))).isEqualTo(1);
  }

  @Test void killedProcessReservationSurvivesAndIsNotReleasedByLateSettlement() throws Exception {
    var dir=java.nio.file.Path.of("target/governance-crash",run.toString()).toAbsolutePath();java.nio.file.Files.createDirectories(dir);
    String authorization="crash-"+run;
    var child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-XX:ActiveProcessorCount=2","-cp",System.getProperty("java.class.path"),GovernanceCorrectnessIT.class.getName(),dir.toString(),run.toString(),attempt.toString(),authorization).redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile()).start();
    try {
      long until=System.nanoTime()+30_000_000_000L;
      while(!java.nio.file.Files.exists(dir.resolve("reserved"))){assertThat(child.isAlive()).isTrue();assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(20);}
      child.destroyForcibly();assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      UUID call=UUID.fromString(java.nio.file.Files.readString(dir.resolve("reserved")));
      // New proxy and DB transactions after a real process kill; there is no TTL refund or unsafe recovery deletion.
      var target=new AgentAudit(db,new dev.workhub.common.Json(new com.fasterxml.jackson.databind.ObjectMapper()),SharedBudgetIT.settings(authorization));
      var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
      long before=db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,authorization);
      assertThat(before).isEqualTo(SharedBudgetIT.HOLD);
      assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE id=?",String.class,bytes(call))).isEqualTo("RESERVED");
      tx.executeWithoutResult(t->target.finish(call,null,null,1,"PROCESS_EXIT_UNKNOWN"));
      tx.executeWithoutResult(t->target.finish(call,10L,5L,1,null));
      long after=db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,authorization);
      assertThat(after).isEqualTo(before);
      new com.fasterxml.jackson.databind.ObjectMapper().writeValue(dir.resolve("result.json").toFile(),Map.of("run",run.toString(),"call",call.toString(),"childExit",child.exitValue(),"before",before,"after",after,"state",db.queryForObject("SELECT state FROM agent_model_call WHERE id=?",String.class,bytes(call)),"httpRequests",0));
    }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
  }
  public static void main(String[] args)throws Exception {
    try(var source=SharedBudgetIT.pool()){
      var db=new org.springframework.jdbc.core.JdbcTemplate(source);
      var audit=new AgentAudit(db,new dev.workhub.common.Json(new com.fasterxml.jackson.databind.ObjectMapper()),SharedBudgetIT.settings(args[3]));
      var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
      UUID call=tx.execute(t->audit.reserve(UUID.fromString(args[1]),UUID.fromString(args[2]),256000,4096));
      java.nio.file.Files.writeString(java.nio.file.Path.of(args[0],"reserved.tmp"),call.toString());
      java.nio.file.Files.move(java.nio.file.Path.of(args[0],"reserved.tmp"),java.nio.file.Path.of(args[0],"reserved"),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      new java.util.concurrent.CountDownLatch(1).await();
    }
  }
}
