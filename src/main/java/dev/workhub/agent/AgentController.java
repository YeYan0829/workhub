package dev.workhub.agent;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects/{p}/agent/runs")
public class AgentController {
  private final AgentRuns runs;private final CurrentActor actors;private final AgentProgress progress;private final AgentProgressStreams streams;
  public AgentController(AgentRuns runs,CurrentActor actors,AgentProgress progress,AgentProgressStreams streams){this.runs=runs;this.actors=actors;this.progress=progress;this.streams=streams;}
  @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
  public AgentRuns.View create(Principal user,@PathVariable UUID p,@RequestBody AgentRuns.Request request,@RequestHeader("Idempotency-Key")String key){return runs.create(actors.from(user),p,request,key);}
  @GetMapping("/{id}")
  public AgentRuns.View get(Principal user,@PathVariable UUID p,@PathVariable UUID id){return runs.get(actors.from(user),p,id);}
  @GetMapping("/{id}/progress")
  public AgentProgress.Snapshot progress(Principal user,@PathVariable UUID p,@PathVariable UUID id){return progress.snapshot(actors.from(user),p,id);}
  @GetMapping(value="/{id}/events",produces="text/event-stream")
  public void events(Principal user,@PathVariable UUID p,@PathVariable UUID id,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    try{streams.open(actors.from(user),p,id,request,response);}
    catch(dev.workhub.common.ApiException e){
      // EventSource accepts only text/event-stream; JSON exception negotiation can hide the 403/404.
      response.setStatus(e.status);response.setHeader("Cache-Control","no-store");
    }
  }
}
