package dev.workhub.agent;

import dev.workhub.common.ApiException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One physical send: authorize/hold/audit -> encode -> send-time admission -> transport -> settlement. */
@Service
public class GovernedModelRequests {
  private final ModelAdmission admission;
  private final AgentAudit audit;
  private final AgentSettings settings;
  public GovernedModelRequests(ModelAdmission admission,AgentAudit audit,AgentSettings settings){this.admission=admission;this.audit=audit;this.settings=settings;}

  public AgentModel.Reply call(UUID run,UUID attempt,AgentModel model,List<Message> messages,List<FunctionTool> definitions,
      long bound,long deadline,Runnable authorize,Runnable onSend) {
    return call(run,attempt,model,messages,definitions,bound,deadline,authorize,onSend,"ANSWER");
  }
  public AgentModel.Reply call(UUID run,UUID attempt,AgentModel model,List<Message> messages,List<FunctionTool> definitions,
      long bound,long deadline,Runnable authorize,Runnable onSend,String purpose) {
    if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Model I/O cannot hold a transaction");
    for(int retry=0;;retry++) {
      UUID call=null;long began=System.nanoTime();
      try {
        remaining(deadline);authorize.run();remaining(deadline);
        // Short transaction only. The monetary hold now covers client preparation and quota waiting.
        call=audit.reserve(run,attempt,bound,AgentSettings.MAX_OUTPUT,purpose);
        audit.trace(run,attempt,"MODEL_INPUT",Map.of("callId",call,"messages",messages,"tools",definitions,"purpose",purpose));
      }catch(RuntimeException e){
        if(call!=null)finishUnsent(call,elapsed(began),"MODEL_NOT_SENT");
        throw e;
      }
      var send=new SendAdmission(run,attempt,deadline,authorize);
      AgentModel.Reply reply=null;RuntimeException failure=null;
      try {
        reply=model.call(messages,definitions,remaining(deadline),()->send.ready(onSend));
        if(reply.inputTokens()<=0 || reply.outputTokens()<0 || reply.inputTokens()>bound
            || reply.outputTokens()>AgentSettings.MAX_OUTPUT || !settings.model.equals(reply.model()))
          throw new ApiException(503,"MODEL_CONTRACT_INVALID","模型身份或用量超出调用合同");
      }catch(RuntimeException e){failure=e;}
      // Cancellation must not prevent the short, idempotent unsent-release transaction.
      boolean interrupted=Thread.interrupted();
      try {
        if(failure!=null) {
          String code=failure instanceof ApiException a?a.code:"MODEL_RESPONSE_INVALID";
          if(send.sent && !(failure instanceof AgentModel.UnsentFailure))audit.finish(call,null,null,elapsed(began),code);
          else audit.finishUnsent(call,elapsed(began),code);
          int status=failure instanceof ModelHttpFailure h?h.httpStatus:0;
          long cooldown=failure instanceof ModelHttpFailure h?h.cooldownMillis:0;
          if(send.permit!=null)admission.outcome(send.permit,status,cooldown);
          audit.trace(run,attempt,"MODEL_FAILURE",Map.of("callId",call,"permitAt",send.permitAt(),"httpStatus",status,"code",code,"cooldownMillis",cooldown));
        }else{
          // Settle known usage before telemetry or Redis errors can obscure a successful send.
          audit.finish(call,reply.inputTokens(),reply.outputTokens(),elapsed(began),null);
          if(send.permit!=null)admission.outcome(send.permit,200,0);
        }
      }finally{
        try{send.finish();}finally{if(interrupted)Thread.currentThread().interrupt();}
      }
      if(failure==null){
        // Progress reads the latest trace; deferred permit accounting must not mask MODEL_OUTPUT.
        audit.trace(run,attempt,"MODEL_OUTPUT",Map.of("callId",call,"reply",reply,"permitAt",send.permitAt()));return reply;
      }
      if(retry==0 && failure instanceof ModelHttpFailure h && h.retryable() && audit.claimRetry(run,attempt)) {
        long pause=Math.max(ThreadLocalRandom.current().nextLong(1000,2001),h.cooldownMillis);
        audit.trace(run,attempt,"MODEL_RETRY",Map.of("callId",call,"delayMillis",pause));
        sleep(run,attempt,deadline,pause,authorize);continue;
      }
      throw failure;
    }
  }

  /** Per-send state only, not another limiter. All decisions still come from ModelAdmission/Lua.
   * Do not write to MySQL after a permit is granted: even wait refunds and traces can outlive 500ms.
   */
  private final class SendAdmission {
    final UUID run,attempt;final long deadline;final Runnable authorize;
    final List<Map<String,Object>> permits=new ArrayList<>(),expired=new ArrayList<>();
    ModelAdmission.Decision permit;long began,grant,end,spent;boolean sent,onSendDone;
    SendAdmission(UUID run,UUID attempt,long deadline,Runnable authorize){this.run=run;this.attempt=attempt;this.deadline=deadline;this.authorize=authorize;}
    long permitAt(){return permit==null?0:permit.at();}
    void ready(Runnable onSend){
      remaining(deadline);began=System.nanoTime();
      try{
        if(admission.enabled()) {grant=audit.reserveWait(run,attempt,15000);end=Math.min(deadline,began+grant*1_000_000L);}
        String previous="";
        while(true){
          // Cancellation and authorization are refreshed immediately BEFORE every acquisition,
          // including after a pause that invalidated a previous permit. No transaction spans wait/I/O.
          check(run,attempt,deadline,authorize);
          if(admission.enabled() && System.nanoTime()>=end)throw new ApiException(503,"MODEL_WAIT_TIMEOUT","单次模型准入等待超时");
          try{permit=admission.acquire();}catch(ApiException e){audit.trace(run,attempt,"MODEL_ADMISSION_ERROR",Map.of("code",e.code,"elapsedMillis",elapsed(began)));throw e;}
          if(permit.permitted()) {
            var event=new LinkedHashMap<String,Object>();event.put("permitAt",permit.at());event.put("waitMillis",elapsed(began));event.put("ticket",permit.ticket());event.put("shared",admission.enabled());
            if(admission.enabled())permits.add(event);
            if(permit.valid()){
              if(!onSendDone){onSend.run();onSendDone=true;}
              remaining(deadline);
              if(permit.valid()){
                event.put("sendReadyAtMillis",System.currentTimeMillis());event.put("permitAgeMicros",admission.enabled()?Math.max(0,(500_000_000L-(permit.validUntilNanos()-System.nanoTime()))/1000):0);
                sent=true;return;
              }
            }
            // A real scheduling pause can still expire a permit. Do not refund tokens or rebuild the client/body.
            admission.outcome(permit,0,0);expired.add(Map.of("permitAt",permit.at(),"ticket",permit.ticket()));permit=null;continue;
          }
          if(Set.of("OPEN","PROBE_BUSY").contains(permit.reason()))throw new ApiException(503,"MODEL_CIRCUIT_OPEN","模型服务熔断或正在恢复探测，请稍后重试");
          if(!Set.of("RATE","COOLDOWN").contains(permit.reason()))throw new ApiException(503,"MODEL_ADMISSION_INVALID","共享准入返回未知状态");
          if(!previous.equals(permit.reason())){audit.trace(run,attempt,"MODEL_WAIT",Map.of("reason",permit.reason(),"delayMillis",permit.delay()));previous=permit.reason();}
          long pause=Math.min(250,Math.min(Math.max(1,permit.delay()),Math.max(1,(end-System.nanoTime())/1_000_000)));
          pause(pause);
        }
      }finally{spent=elapsed(began);}
    }
    void finish(){
      if(grant>0){audit.finishWait(run,attempt,grant,spent);audit.trace(run,attempt,"MODEL_ADMISSION_WAIT",Map.of("elapsedMillis",spent));}
      for(var event:permits)audit.trace(run,attempt,"MODEL_PERMIT",event);
      for(var event:expired)audit.trace(run,attempt,"MODEL_PERMIT_EXPIRED",event);
    }
  }
  private void finishUnsent(UUID call,long millis,String code){boolean interrupted=Thread.interrupted();try{audit.finishUnsent(call,millis,code);}finally{if(interrupted)Thread.currentThread().interrupt();}}
  private void sleep(UUID run,UUID attempt,long deadline,long millis,Runnable authorize){
    check(run,attempt,deadline,authorize);
    if(remaining(deadline)<millis)throw new ApiException(503,"AGENT_TIMEOUT","剩余执行时间不足以等待");
    if(millis>15000)throw new ApiException(503,"MODEL_WAIT_BUDGET","单次退避超过等待额度");
    long began=System.nanoTime(),grant=audit.reserveWait(run,attempt,millis);
    try{if(grant<millis)throw new ApiException(503,"MODEL_WAIT_BUDGET","模型等待累计额度不足");pause(millis);}
    finally{long spent=elapsed(began);audit.finishWait(run,attempt,grant,spent);audit.trace(run,attempt,"MODEL_BACKOFF",Map.of("elapsedMillis",spent));}
  }
  private void pause(long millis){
    try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"AGENT_TIMEOUT","模型等待被中断");}
  }
  private void check(UUID run,UUID attempt,long deadline,Runnable authorize){remaining(deadline);authorize.run();audit.check(run,attempt);remaining(deadline);}
  private static long remaining(long deadline){long value=(deadline-System.nanoTime())/1_000_000;if(value<=0 || Thread.currentThread().isInterrupted())throw new ApiException(503,"AGENT_TIMEOUT","运行已超时");return value;}
  private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
}
