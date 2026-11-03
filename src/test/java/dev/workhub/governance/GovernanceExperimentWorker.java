package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Two real JVM workers, production governance/accounting/HTTP; fixed three-step test tasks. */
public class GovernanceExperimentWorker {
  static final ObjectMapper JSON=new ObjectMapper();
  // Experiment-only B adapter. Same continuous token refill, polling decisions and 500 ms receipt lifetime.
  static class LocalGate extends ModelAdmission {
    double tokens=1;long at=System.nanoTime(),cooldown=0;final double rate;
    LocalGate(double rate){super(false,"",rate,1,"127.0.0.1",1,"");this.rate=rate;}
    @Override public boolean enabled(){return true;}
    @Override public synchronized Decision acquire(){
      long now=System.nanoTime();
      if(now<cooldown)return new Decision("COOLDOWN",System.currentTimeMillis(),Math.max(1,(cooldown-now)/1_000_000),"local",now+500_000_000);
      tokens=Math.min(1,tokens+Math.max(0,now-at)*rate/1e9);at=Math.max(at,now);
      if(tokens<1)return new Decision("RATE",System.currentTimeMillis(),(long)Math.ceil((1-tokens)*1000/rate),"local",now+500_000_000);
      tokens--;return new Decision("PERMIT",System.currentTimeMillis(),0,"local",now+500_000_000);
    }
    @Override public synchronized void outcome(Decision d,int status,long delay){if(status==429&&delay>=0){cooldown=Math.max(cooldown,System.nanoTime()+delay*1_000_000);tokens=0;at=cooldown;}}
  }
  public static void main(String[] args)throws Exception{
    Path dir=Path.of(args[0]);int index=Integer.parseInt(args[1]);
    Map<String,Object> c=JSON.readValue(dir.resolve("config.json").toFile(),Map.class);
    String strategy=(String)c.get("strategy"),scope=(String)c.get("scope"),authorization=(String)c.get("authorization");
    int n=((List<Number>)c.get("distribution")).get(index).intValue();double rate=((Number)c.get("rate")).doubleValue();
    try(var source=SharedBudgetIT.pool()){
      var db=new JdbcTemplate(source);Flyway.configure().dataSource(source).load().migrate();
      var settings=new AgentSettings(true,false,(String)c.get("endpoint"),"local-synthetic","glm-4.7",authorization,BigDecimal.valueOf(100),0,0);
      ReflectionTestUtils.setField(settings,"inputPrice",2L);ReflectionTestUtils.setField(settings,"outputPrice",5L);
      var probe="1".equals(System.getenv("WORKHUB_PERMIT_TIMING"))?new PermitTimingProbe():null;
      javax.sql.DataSource auditSource=probe==null?source:probe.source(source);
      var proxy=new ProxyFactory(new AgentAudit(new JdbcTemplate(auditSource),new Json(JSON),settings));proxy.setProxyTargetClass(true);
      proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(auditSource),new AnnotationTransactionAttributeSource()));var transactionalAudit=(AgentAudit)proxy.getProxy();
      var audit=probe==null?transactionalAudit:probe.audit(transactionalAudit);
      ModelAdmission realGate=switch(strategy){case "B"->new LocalGate(rate/2);case "C"->ModelAdmissionIT.gate(scope,rate,2);default->new ModelAdmission(false,"",rate,2,"127.0.0.1",1,"");};
      ModelAdmission gate=probe==null?realGate:probe.gate(realGate);
      try{
        UUID user=UUID.randomUUID(),workspace=UUID.randomUUID(),project=UUID.randomUUID();
        db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'Comparison','unused')",bytes(user),"cmp-"+user);
        db.update("INSERT INTO workspace(id,name) VALUES (?,'Governance comparison')",bytes(workspace));
        db.update("INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Comparison')",bytes(project),bytes(workspace));
        var requests=new GovernedModelRequests(gate,audit,settings);var model=probe==null?new GlmChatModel(settings):probe.model(settings);
        // Warm the actual adapter class path before simultaneous measured submission; separate server quota domain.
        var warm=new AgentSettings(true,false,(String)c.get("warmEndpoint"),"local-synthetic","glm-4.7",authorization,BigDecimal.ONE,0,0);
        new GlmChatModel(warm).call(List.of(new UserMessage("warmup")),List.of(),5000);
        var ids=new ArrayList<UUID>();var attempts=new ArrayList<UUID>();
        for(int i=0;i<n;i++){UUID run=UUID.randomUUID(),attempt=UUID.randomUUID();ids.add(run);attempts.add(attempt);
          db.update("INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision,status,attempt_token) VALUES (?,?,?,'Fixed three-step governance task','{}',0,'RUNNING',?)",bytes(run),bytes(project),bytes(user),bytes(attempt));}
        var executor=Executors.newFixedThreadPool(12);var ready=new CountDownLatch(n);var start=new CountDownLatch(1);
        try{
          var futures=new ArrayList<Future<Map<String,Object>>>();
          for(int i=0;i<n;i++){final int task=i;futures.add(executor.submit(()->{
            UUID run=ids.get(task),attempt=attempts.get(task);ready.countDown();start.await();
            long submitted=Long.parseLong(Files.readString(dir.resolve("go"))),began=System.nanoTime();
            long deadline=began+Math.max(0,submitted+((Number)c.get("deadlineMs")).longValue()-System.currentTimeMillis())*1_000_000;
            var result=new LinkedHashMap<String,Object>();result.put("run",run.toString());result.put("instance",index);result.put("pid",ProcessHandle.current().pid());result.put("submittedAtMs",submitted);int completed=0;
            String code="",status="SUCCESS";
            try{for(int step=0;step<3;step++){requests.call(run,attempt,model,List.of(new UserMessage(run+":"+step)),List.of(),2000,deadline,()->{},()->{});completed++;}
              if(System.nanoTime()>deadline){status="TIMEOUT";code="AGENT_TIMEOUT";}
            }catch(Exception e){code=e instanceof ApiException a?a.code:e.getClass().getSimpleName();status=code.contains("TIMEOUT")||code.contains("WAIT_BUDGET")?"TIMEOUT":code.contains("BUDGET")||code.contains("RATE_LIMIT")||code.contains("ADMISSION")||code.contains("CIRCUIT")?"REJECTED":"FAILED";}
            result.put("endedAtMs",System.currentTimeMillis());result.put("latencyMs",System.currentTimeMillis()-submitted);result.put("status",status);result.put("error",code);result.put("completedSteps",completed);
            result.put("calls",db.queryForList("SELECT HEX(id) call_id,state,reserved_nano_usd,estimated_nano_usd,error_code,elapsed_millis FROM agent_model_call WHERE run_id=? ORDER BY created_at",bytes(run)));
            result.put("trace",db.queryForList("SELECT kind,payload FROM agent_trace WHERE run_id=? ORDER BY id",bytes(run)));
            result.put("accounting",db.queryForMap("SELECT model_wait_millis,model_retries FROM agent_run WHERE id=?",bytes(run)));
            return result;
          }));}
          ready.await();Files.writeString(dir.resolve("ready-"+index),""+ProcessHandle.current().pid());
          long until=System.nanoTime()+60_000_000_000L;while(!Files.exists(dir.resolve("go"))){if(System.nanoTime()>until)throw new IllegalStateException("barrier timeout");Thread.sleep(10);}start.countDown();
          var results=new ArrayList<Map<String,Object>>();for(var f:futures)results.add(f.get(40,TimeUnit.SECONDS));
          JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("tasks-"+index+".json").toFile(),results);
          var ledger=db.queryForMap("SELECT * FROM agent_budget WHERE authorization_id=?",authorization);ledger.put("capturedAtMs",System.currentTimeMillis());JSON.writeValue(dir.resolve("ledger-"+index+".json").toFile(),ledger);
        }finally{executor.shutdownNow();}
      }finally{gate.close();if(probe!=null)probe.write(dir.resolve("timing-"+index+".json"));}
    }
  }
}
