package dev.workhub.agent;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects/{p}/agent")
public class TriageProposalController {
  private final TriageProposals proposals;private final AgentRuns runs;private final CurrentActor actors;
  public TriageProposalController(TriageProposals proposals,AgentRuns runs,CurrentActor actors){this.proposals=proposals;this.runs=runs;this.actors=actors;}
  @PostMapping("/triage/{task}/runs")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public AgentRuns.View analyze(Principal user,@PathVariable UUID p,@PathVariable UUID task,@RequestBody AgentRuns.TriageRequest request,@RequestHeader("Idempotency-Key")String key){return runs.createTriage(actors.from(user),p,task,request,key);}
  @PostMapping("/runs/{run}/proposal")
  public TriageProposals.View create(Principal user,@PathVariable UUID p,@PathVariable UUID run){return proposals.create(actors.from(user),p,run);}
  @GetMapping("/triage/{task}/proposals")
  public List<UUID> list(Principal user,@PathVariable UUID p,@PathVariable UUID task){return proposals.list(actors.from(user),p,task);}
  @GetMapping("/triage/{task}/proposals/history")
  public AgentHistory.Page<TriageProposals.HistoryItem> history(Principal user,@PathVariable UUID p,@PathVariable UUID task,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="30")int limit){return proposals.history(actors.from(user),p,task,cursor,limit);}
  @GetMapping("/triage/{task}/runs")
  public AgentHistory.Page<AgentRuns.HistoryItem> runs(Principal user,@PathVariable UUID p,@PathVariable UUID task,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="30")int limit){return runs.triageHistory(actors.from(user),p,task,cursor,limit);}
  @GetMapping("/proposals/{id}")
  public TriageProposals.View get(Principal user,@PathVariable UUID p,@PathVariable UUID id){return proposals.get(actors.from(user),p,id);}
  @PostMapping("/proposals/{id}/versions")
  public TriageProposals.View edit(Principal user,@PathVariable UUID p,@PathVariable UUID id,@RequestBody TriageProposals.Edit request){return proposals.edit(actors.from(user),p,id,request);}
  @PostMapping("/proposals/{id}/preview")
  public TriageProposals.View preview(Principal user,@PathVariable UUID p,@PathVariable UUID id,@RequestBody TriageProposals.Edit request){return proposals.preview(actors.from(user),p,id,request);}
  @PostMapping("/proposals/{id}/approve")
  public TriageProposals.View approve(Principal user,@PathVariable UUID p,@PathVariable UUID id,@RequestBody TriageProposals.Approval request){return proposals.approve(actors.from(user),p,id,request);}
}
