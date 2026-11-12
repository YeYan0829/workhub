package dev.workhub.agent;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class TeamModelController {
  private final TeamModels service;
  private final CurrentActor actors;

  public TeamModelController(TeamModels service, CurrentActor actors) {
    this.service = service;
    this.actors = actors;
  }

  @GetMapping("/workspaces/{w}/model-catalog")
  public Object catalog(Principal p, @PathVariable UUID w) {
    return service.catalog(actors.from(p), w);
  }

  @GetMapping("/workspaces/{w}/model-connection")
  public Object connection(Principal p, @PathVariable UUID w) {
    return service.getConnection(actors.from(p), w);
  }

  @PutMapping("/workspaces/{w}/model-connection")
  public Object connection(
      Principal p, @PathVariable UUID w, @RequestBody TeamModels.ConnectionUpdate r) {
    return service.updateConnection(actors.from(p), w, r);
  }

  @GetMapping("/projects/{project}/model-policy")
  public Object policy(Principal p, @PathVariable UUID project) {
    return service.getPolicy(actors.from(p), project);
  }

  @PutMapping("/projects/{project}/model-policy")
  public Object policy(
      Principal p, @PathVariable UUID project, @RequestBody TeamModels.PolicyUpdate r) {
    return service.updatePolicy(actors.from(p), project, r);
  }

  @GetMapping("/workspaces/{w}/model-usage")
  public Object usage(
      Principal p,
      @PathVariable UUID w,
      @RequestParam(required = false) UUID project,
      @RequestParam(required = false) LocalDate period,
      @RequestParam(defaultValue = "100") int limit) {
    return service.usage(actors.from(p), w, project, period, limit);
  }

  @GetMapping("/projects/{project}/model-usage")
  public Object usage(
      Principal p,
      @PathVariable UUID project,
      @RequestParam(required = false) LocalDate period,
      @RequestParam(defaultValue = "100") int limit) {
    return service.projectUsage(actors.from(p), project, period, limit);
  }
}
