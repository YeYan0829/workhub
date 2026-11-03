package dev.workhub.agent;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects/{p}/agent")
public class AutomaticTriageController {
  private final AutomaticTriage triage;private final CurrentActor actors;
  public AutomaticTriageController(AutomaticTriage triage,CurrentActor actors){this.triage=triage;this.actors=actors;}
  @GetMapping("/triage-settings")
  public AutomaticTriage.Configuration get(Principal user,@PathVariable UUID p){return triage.get(actors.from(user),p);}
  @PutMapping("/triage-settings")
  public AutomaticTriage.Configuration update(Principal user,@PathVariable UUID p,@RequestBody AutomaticTriage.Update request){return triage.update(actors.from(user),p,request);}
  @GetMapping("/triage/{task}/status")
  public AutomaticTriage.Status status(Principal user,@PathVariable UUID p,@PathVariable UUID task){return triage.status(actors.from(user),p,task);}
}
