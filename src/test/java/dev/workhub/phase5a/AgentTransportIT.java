package dev.workhub.phase5a;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.agent.AgentContracts.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Actual scheduled publisher and Rabbit listener; never calls a paid provider. */
@SpringBootTest(properties={"workhub.messaging.enabled=true","workhub.messaging.poll-ms=100",
    "workhub.messaging.consumer-enabled=false","workhub.agent.enabled=true",
    "workhub.retrieval.enabled=false","workhub.embedding.enabled=false","workhub.github.enabled=false",
    "workhub.agent.budget-usd=0","workhub.bootstrap.login=","debug=false","logging.level.root=INFO"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AgentTransportIT {
  static final MockWebServer MODEL=new MockWebServer();
  static {try{MODEL.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv().getOrDefault("MYSQL_PORT","13306")+"/workhub_phase5a_mq_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    p.add("spring.rabbitmq.virtual-host",()->"phase5a_test");
    p.add("workhub.agent.endpoint",()->MODEL.url("/api/paas/v4/chat/completions").toString());
    p.add("workhub.agent.model",()->"glm-4.7");
    p.add("workhub.agent.paid-approved",()->false);
    p.add("workhub.agent.api-key",()->"loopback-not-a-real-key");
    p.add("workhub.agent.authorization-id",()->"p5a-mq-"+MODEL.getPort());
  }
  @Autowired JdbcTemplate db;@Autowired Json json;@Autowired ObjectMapper mapper;
  @Autowired ProjectService projects;@Autowired TaskService tasks;@Autowired AgentRuns runs;
  @Autowired OutboxRepository outbox;@Autowired RabbitPublisher publisher;
  @Autowired RabbitListenerEndpointRegistry listeners;
  @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
  private long queueDepth(){return rabbit.execute(ch->ch.messageCount("agent.read"));}

  @AfterAll static void close()throws Exception{MODEL.shutdown();}
  @Test void scheduledPublicationConsumerRestartAndDuplicateDelivery()throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("workhub_phase5a_mq_test");
    listeners.stop();UUID user=UUID.randomUUID(),workspace=UUID.randomUUID();var actor=new ActorContext(user);
    db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,'unused')",bytes(user),"mq-p5a-"+user,"MQ fixture");
    db.update("INSERT INTO workspace(id,name) VALUES (?,'Agent MQ isolated fixture')",bytes(workspace));
    db.update("INSERT INTO workspace_member(workspace_id,user_id) VALUES (?,?)",bytes(workspace),bytes(user));
    var project=projects.create(actor,workspace,"Agent MQ",UUID.randomUUID().toString()).id();
    try {
      var task=tasks.create(actor,project,new TaskRequests.Create("MQ 登录任务",null,TaskStatus.TODO,TaskType.FEATURE,1,user,null),UUID.randomUUID().toString()).id();
      var calls=new AtomicInteger();MODEL.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
        try {
          Object message;String finish;
          if(calls.getAndIncrement()==0){finish="tool_calls";message=Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","mq_tool","type","function","function",Map.of("name","get_task","arguments",json.write(Map.of("taskId",task))))));}
          else {finish="stop";var q=mapper.readTree(request.getBody().readUtf8());
            assertThat(q.path("tools")).hasSize(12);String ref=null;
            for(var m:q.path("messages"))if(m.path("role").asText().equals("tool"))for(var source:mapper.readTree(m.path("content").asText()).path("citations"))if(source.path("kind").asText().equals("FACT")&&source.path("dataPath").asText().equals("data")){ref=source.path("id").asText();break;}
            message=Map.of("role","assistant","content",json.write(Map.of("paragraphs",List.of(Map.of("text","MQ 登录任务 TODO","citations",List.of(ref))))));
          }
          return new MockResponse().setHeader("Content-Type","application/json").setBody(json.write(Map.of("id","mq-model","model","glm-4.7","choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",Map.of("prompt_tokens",100,"completion_tokens",60,"total_tokens",160))));
        }catch(Exception e){return new MockResponse().setResponseCode(500);}
      }});
      String before=json.write(tasks.get(actor,project,task));
      var run=runs.create(actor,project,new AgentRuns.Request("MQ 登录任务是什么状态？"),"mq-question");
      assertThat(runs.create(actor,project,new AgentRuns.Request("MQ 登录任务是什么状态？"),"mq-question").id()).isEqualTo(run.id());
      var envelopeId=db.queryForObject("SELECT o.id FROM outbox_message o JOIN work_job j ON o.job_id=j.id WHERE j.owner_ref=? AND j.handler='RUN_AGENT'",(r,n)->read(r,"id"),bytes(run.id()));
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(db.queryForObject("SELECT publish_state FROM outbox_message WHERE id=?",String.class,bytes(envelopeId))).isEqualTo("PUBLISHED"));
      assertThat(runs.get(actor,project,run.id()).status()).isEqualTo("QUEUED");assertThat(calls.get()).isZero();
      listeners.start();
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(()->assertThat(runs.get(actor,project,run.id()).status()).isEqualTo("SUCCEEDED"));
      assertThat(calls.get()).isEqualTo(2);assertThat(json.write(tasks.get(actor,project,task))).isEqualTo(before);
      listeners.stop();var envelope=outbox.find(envelopeId,false);publisher.publish(envelope);
      assertThat(queueDepth()).isGreaterThanOrEqualTo(1L);
      listeners.start();
      // Queue drain is observed after restart, then model-call count must stay unchanged.
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE run_id=?",Long.class,bytes(run.id()))).isEqualTo(2L);
        assertThat(outbox.find(envelopeId,false).disposition()).isEqualTo("SUCCEEDED");
        assertThat(queueDepth()).isZero();
      });
      Thread.sleep(500);assertThat(calls.get()).isEqualTo(2);
      Path dest=Path.of(System.getProperty("phase5a.output","target/evidence/phase5a/runs/mq-local"));Files.createDirectories(dest);
      Files.writeString(dest.resolve("rabbit.json"),json.write(Map.of("modelMode","loopback-fixture","database","workhub_phase5a_mq_test","vhost","phase5a_test","runId",run.id(),"status",runs.get(actor,project,run.id()).status(),"modelCalls",calls.get(),"checks",List.of("scheduled outbox publication","consumer outage and restart","native tool loop","duplicate message has no extra model call","business task unchanged"))));
    }finally{
      db.update("UPDATE project_member SET active=FALSE WHERE project_id=?",bytes(project));
      db.update("UPDATE workspace_member SET active=FALSE WHERE workspace_id=?",bytes(workspace));
      db.update("UPDATE app_user SET active=FALSE WHERE id=?",bytes(user));
    }
  }
}
