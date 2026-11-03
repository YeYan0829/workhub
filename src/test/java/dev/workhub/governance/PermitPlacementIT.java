package dev.workhub.governance;

import static org.assertj.core.api.Assertions.*;
import static dev.workhub.common.Ids.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Uses the same real MySQL/Redis/Glm HTTP fixture; delays are injected around production boundaries. */
class PermitPlacementIT extends GovernedModelRequestsIT {
  final AtomicInteger permits=new AtomicInteger();
  void observeGate(boolean pauseFirstGrant){
    ModelAdmission real=gate;
    gate=new ModelAdmission(false,"",1,1,"127.0.0.1",1,""){
      public boolean enabled(){return true;}
      public Decision acquire(){var d=real.acquire();if(d.permitted()&&permits.incrementAndGet()==1&&pauseFirstGrant)pause(650);return d;}
      public void outcome(Decision d,int status,long delay){real.outcome(d,status,delay);}
      public void close(){real.close();}
    };
    requests=new GovernedModelRequests(gate,audit,settings);
  }
  static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"AGENT_TIMEOUT","injected pause interrupted");}}
  AgentModel slowSetup(long millis,AtomicInteger setups){return new GlmChatModel(settings){
    public Reply call(List<Message> messages,List<FunctionTool> tools,long remaining,Runnable before){setups.incrementAndGet();pause(millis);return super.call(messages,tools,remaining,before);}
  };}
  void released(){assertThat(model.getRequestCount()).isZero();assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,settings.authorization)).isZero();assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(run))).isEqualTo("COMPLETE");}
  @Test void slowAuditAndClientPreparationUseOnlyOnePermit(){
    // Delay MODEL_INPUT auditing before readiness, with the real transaction proxy still in place.
    var p=new ProxyFactory(audit);p.setProxyTargetClass(true);p.addAdvice((MethodInterceptor)inv->{if(inv.getMethod().getName().equals("trace")&&"MODEL_INPUT".equals(inv.getArguments()[2]))pause(650);return inv.proceed();});audit=(AgentAudit)p.getProxy();
    observeGate(false);ok();var setups=new AtomicInteger();
    requests.call(run,attempt,slowSetup(650,setups),List.of(new UserMessage("slow preparation")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(8),()->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();},dispatched::incrementAndGet);
    assertThat(setups.get()).isEqualTo(1);assertThat(permits.get()).isEqualTo(1);assertThat(model.getRequestCount()).isEqualTo(1);assertThat(audit.calls(run)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_PERMIT_EXPIRED'",Integer.class,bytes(run))).isZero();
  }
  @Test void pauseAfterGrantRejectsOldPermitWithoutRebuildingOrRefund(){
    observeGate(true);ok();var setups=new AtomicInteger();
    requests.call(run,attempt,slowSetup(0,setups),List.of(new UserMessage("post grant pause")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(8),()->{},dispatched::incrementAndGet);
    assertThat(permits.get()).isEqualTo(2);assertThat(setups.get()).isEqualTo(1);assertThat(model.getRequestCount()).isEqualTo(1);assertThat(dispatched.get()).isEqualTo(1);assertThat(audit.calls(run)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_PERMIT_EXPIRED'",Integer.class,bytes(run))).isEqualTo(1);
  }
  @Test void cancellationDuringPreparationSendsNothingAndReleasesHold(){
    observeGate(false);
    var cancel=new GlmChatModel(settings){public Reply call(List<Message> m,List<FunctionTool> t,long r,Runnable before){db.update("UPDATE agent_run SET stop_requested=TRUE WHERE id=?",bytes(run));return super.call(m,t,r,before);}};
    assertThatThrownBy(()->requests.call(run,attempt,cancel,List.of(new UserMessage("cancel")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),()->{},()->{})).hasFieldOrPropertyWithValue("code","AGENT_ATTEMPT_SUPERSEDED");
    assertThat(permits.get()).isZero();released();
  }
  @Test void deadlineDuringPreparationNeverConsumesPermit(){
    observeGate(false);
    assertThatThrownBy(()->requests.call(run,attempt,slowSetup(650,new AtomicInteger()),List.of(new UserMessage("deadline")),List.of(),2000,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300),()->{},()->{})).hasFieldOrPropertyWithValue("code","AGENT_TIMEOUT");
    assertThat(permits.get()).isZero();released();
  }
  @Test void cancellationWhileWaitingReleasesMoneyOutsideTransaction()throws Exception{
    cooldown(30000);observeGate(false);var executor=Executors.newSingleThreadExecutor();
    try{
      var future=executor.submit(()->call(6000));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
      while(db.queryForObject("SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_WAIT'",Integer.class,bytes(run))==0){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(10);}
      assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,settings.authorization)).isEqualTo(24480);
      db.update("UPDATE agent_run SET stop_requested=TRUE WHERE id=?",bytes(run));
      assertThatThrownBy(()->future.get(4,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);assertThat(permits.get()).isZero();released();
    }finally{executor.shutdownNow();}
  }
  @Test void quotaWaitReducesActualHttpReadTimeout(){
    // Admission consumes ~800ms; a subsequent 2s body delay must not get the original entire deadline again.
    cooldown(800);observeGate(false);model.enqueue(new okhttp3.mockwebserver.MockResponse().setBodyDelay(2,TimeUnit.SECONDS).setBody("{}"));
    long start=System.nanoTime();assertThatThrownBy(()->call(1500)).hasFieldOrPropertyWithValue("code","GLM_TIMEOUT");
    assertThat((System.nanoTime()-start)/1_000_000).isLessThan(2300);assertThat(model.getRequestCount()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM agent_model_call WHERE run_id=?",String.class,bytes(run))).isEqualTo("UNKNOWN");
  }

  @Test void slowEvidenceObserverRunsBeforeAdmission(){
    observeGate(false);ok();
    var observed=new GlmChatModel(settings,wire->{if(wire.phase().equals("REQUEST")){assertThat(permits.get()).isZero();pause(650);}});
    requests.call(run,attempt,observed,List.of(new UserMessage("observer")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(8),()->{},()->{});
    assertThat(permits.get()).isEqualTo(1);assertThat(model.getRequestCount()).isEqualTo(1);
  }
  @Test void adapterProvenUnsentAfterGuardStillReleasesHold(){
    observeGate(false);
    var noTransport=new GlmChatModel(settings){public Reply call(List<Message> m,List<FunctionTool> t,long r,Runnable before){before.run();throw new AgentModel.UnsentFailure("AGENT_TIMEOUT","known pre-transport deadline");}};
    assertThatThrownBy(()->requests.call(run,attempt,noTransport,List.of(new UserMessage("no transport")),List.of(),2000,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),()->{},()->{})).hasFieldOrPropertyWithValue("code","AGENT_TIMEOUT");
    assertThat(permits.get()).isEqualTo(1);released();
  }

  @Test void deferredPermitTelemetryDoesNotMaskOutputProgress(){
    observeGate(false);ok();call(5000);
    assertThat(db.queryForObject("SELECT kind FROM agent_trace WHERE run_id=? ORDER BY id DESC LIMIT 1",String.class,bytes(run))).isEqualTo("MODEL_OUTPUT");
  }
}
