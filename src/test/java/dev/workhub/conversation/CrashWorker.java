package dev.workhub.conversation;

import dev.workhub.WorkHubApplication;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.messaging.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate JVM, test classpath only. Fault injection never enters the production package. */
public class CrashWorker {
  @Configuration static class Fault {
    @EventListener public void boundary(RunCheckpointStore.Boundary event){
      if(event.calls()==1 && event.phase().equals(System.getProperty("conversation.crash")))Runtime.getRuntime().halt(86);
    }
  }
  public static void main(String[] args){
    try(var app=new SpringApplicationBuilder(WorkHubApplication.class,Fault.class).run(
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:23316/workhub_conversation_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "--spring.datasource.username=conversation","--spring.datasource.password=conversation-local-test","--workhub.bootstrap.login=",
        "--server.port=0","--workhub.messaging.enabled=false","--workhub.retrieval.enabled=false","--workhub.embedding.enabled=false","--workhub.github.enabled=false",
        "--workhub.agent.enabled=true","--workhub.agent.endpoint="+System.getProperty("conversation.endpoint"),"--workhub.agent.api-key=fixture-only","--workhub.agent.model=glm-4.7",
        "--workhub.agent.authorization-id="+System.getProperty("conversation.authorization"),"--workhub.agent.paid-approved=false","--workhub.agent.budget-usd=0","--debug=false","--logging.level.root=WARN")){
      var outbox=app.getBean(OutboxRepository.class);var db=app.getBean(JdbcTemplate.class);var executor=app.getBean(WorkJobExecutor.class);
      UUID run=UUID.fromString(args[0]);var id=db.queryForObject("SELECT o.id FROM outbox_message o JOIN work_job j ON j.id=o.job_id WHERE j.owner_ref=? ORDER BY o.created_at DESC LIMIT 1",(r,n)->Ids.read(r,"id"),Ids.bytes(run));
      var e=outbox.find(id,false);executor.handle(e.id().toString(),e.destination(),e.wire().getBytes(StandardCharsets.UTF_8));
    }
  }
}
