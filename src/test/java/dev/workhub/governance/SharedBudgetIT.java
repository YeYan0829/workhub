package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** No model adapter is constructed. Prices and authorization are synthetic. */
public class SharedBudgetIT {
  static final ObjectMapper JSON=new ObjectMapper();
  static final long HOLD=256000L*2+4096L*5;
  static HikariDataSource pool(){
    var c=new HikariConfig();
    c.setJdbcUrl("jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","25306")+"/workhub_phase5a_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC");
    c.setUsername(System.getenv().getOrDefault("MYSQL_USER","workhub"));
    c.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD","workhub_w2_fixture"));
    c.setMaximumPoolSize(5);c.setMinimumIdle(1);c.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    return new HikariDataSource(c);
  }
  static AgentSettings settings(String authorization){
    var s=new AgentSettings(true,false,"http://127.0.0.1:1/chat/completions","synthetic-never-sent","glm-4.7",authorization,BigDecimal.valueOf(HOLD*5,9),0,0);
    ReflectionTestUtils.setField(s,"inputPrice",2L);ReflectionTestUtils.setField(s,"outputPrice",5L);return s;
  }
  @RepeatedTest(3) void twoProcessesTwentyContendersAndFiveHolds()throws Exception {
    Path dir=Path.of("target/shared-budget",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(dir);
    String authorization="w2-synthetic-"+UUID.randomUUID();
    try(var source=pool()) {
      Flyway.configure().dataSource(source).load().migrate();
      var db=new JdbcTemplate(source);assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_test");
      UUID user=UUID.randomUUID();db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'Synthetic budget fixture','unused')",bytes(user),"w2-"+user);
      var projects=new ArrayList<UUID>();
      for(int w=0;w<2;w++) {UUID workspace=UUID.randomUUID();db.update("INSERT INTO workspace(id,name) VALUES (?,'W2 synthetic')",bytes(workspace));
        for(int p=0;p<2;p++){UUID project=UUID.randomUUID();db.update("INSERT INTO project(id,workspace_id,name) VALUES (?,?,'W2 budget fixture')",bytes(project),bytes(workspace));projects.add(project);}}
      var inputs=new ArrayList<Map<String,String>>();
      for(int i=0;i<20;i++){UUID run=UUID.randomUUID(),attempt=UUID.randomUUID();
        db.update("INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision,status,attempt_token) VALUES (?,?,?,'Synthetic budget contention','{}',0,'RUNNING',?)",bytes(run),bytes(projects.get(i%4)),bytes(user),bytes(attempt));
        inputs.add(Map.of("run",run.toString(),"attempt",attempt.toString()));}
      var children=new ArrayList<Process>();
      try {
        for(int i=0;i<2;i++){
          JSON.writeValue(dir.resolve("input-"+i+".json").toFile(),inputs.subList(i*10,i*10+10));
          children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),SharedBudgetIT.class.getName(),dir.toString(),""+i,authorization).redirectErrorStream(true).redirectOutput(dir.resolve("child-"+i+".log").toFile()).start());}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!(Files.exists(dir.resolve("ready-0"))&&Files.exists(dir.resolve("ready-1")))) {assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(20);}
        Files.createFile(dir.resolve("go"));
        for(var child:children){assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}
        var results=new ArrayList<Map<String,Object>>();for(int i=0;i<2;i++)results.addAll(JSON.readValue(dir.resolve("result-"+i+".json").toFile(),List.class));
        long balance=db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,authorization);
        JSON.writeValue(dir.resolve("summary.json").toFile(),Map.of("authorization",authorization,"results",results,"limit",HOLD*5,"heldBeforeCompletion",balance,"projects",4,"workspaces",2,"httpRequests",0));
        assertThat(results.stream().filter(r->r.get("status").equals("RESERVED")).count()).isEqualTo(5);
        assertThat(results.stream().filter(r->r.get("status").equals("MODEL_COST_BUDGET")).count()).isEqualTo(15);
        assertThat(balance).isEqualTo(HOLD*5);
        var audit=new AgentAudit(db,new Json(JSON),settings(authorization));var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var ids=results.stream().filter(r->r.get("status").equals("RESERVED")).map(r->UUID.fromString((String)r.get("call"))).toList();
        for(int i=0;i<3;i++)tx.executeWithoutResult(s->audit.finish(ids.get(0),10L,10L,1,null));
        long afterKnown=HOLD*4+70;assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,authorization)).isEqualTo(afterKnown);
        for(int i=0;i<3;i++){tx.executeWithoutResult(s->audit.finish(ids.get(1),null,null,1,"SYNTHETIC_UNKNOWN"));tx.executeWithoutResult(s->audit.finish(ids.get(1),10L,10L,1,null));}
        assertThat(db.queryForObject("SELECT reserved_nano_usd FROM agent_budget WHERE authorization_id=?",Long.class,authorization)).isEqualTo(afterKnown);
        JSON.writeValue(dir.resolve("completion.json").toFile(),Map.of("knownCompletionDeliveries",3,"unknownAndLateCompletionPairs",3,"expectedFinalHeld",afterKnown,"actualFinalHeld",afterKnown));
      }finally{for(var child:children)if(child.isAlive())child.destroyForcibly();}
    }
  }
  public static void main(String[] args)throws Exception {
    Path dir=Path.of(args[0]);String index=args[1];
    try(var source=pool()){
      var db=new JdbcTemplate(source);if(!"workhub_phase5a_test".equals(db.queryForObject("SELECT DATABASE()",String.class)))throw new IllegalStateException("Wrong database");
      var audit=new AgentAudit(db,new Json(JSON),settings(args[2]));var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
      List<Map<String,String>> inputs=JSON.readValue(dir.resolve("input-"+index+".json").toFile(),List.class);
      var executor=Executors.newFixedThreadPool(10);var ready=new CountDownLatch(10);var start=new CountDownLatch(1);
      try{
        var futures=new ArrayList<Future<Map<String,Object>>>();
        for(var input:inputs)futures.add(executor.submit(()->{ready.countDown();start.await();
          var result=new LinkedHashMap<String,Object>();result.putAll(input);result.put("pid",ProcessHandle.current().pid());
          try{UUID call=tx.execute(s->audit.reserve(UUID.fromString(input.get("run")),UUID.fromString(input.get("attempt")),256000,4096));result.put("status","RESERVED");result.put("call",call.toString());}
          catch(ApiException e){result.put("status",e.code);}catch(RuntimeException e){result.put("status",e.getClass().getSimpleName());}
          return result;}));
        ready.await();Files.createFile(dir.resolve("ready-"+index));while(!Files.exists(dir.resolve("go")))Thread.sleep(10);start.countDown();
        var results=new ArrayList<Map<String,Object>>();for(var f:futures)results.add(f.get(20,TimeUnit.SECONDS));JSON.writeValue(dir.resolve("result-"+index+".json").toFile(),results);
      }finally{executor.shutdownNow();}
    }
  }
}
