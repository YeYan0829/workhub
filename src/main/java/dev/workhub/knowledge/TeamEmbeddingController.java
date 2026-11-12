package dev.workhub.knowledge;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class TeamEmbeddingController {
  private final TeamEmbeddings teams;
  private final CurrentActor actors;

  public TeamEmbeddingController(TeamEmbeddings teams, CurrentActor actors) {
    this.teams = teams;
    this.actors = actors;
  }

  @GetMapping("/workspaces/{w}/embedding-catalog")
  public Object catalog(Principal a, @PathVariable UUID w) {
    return teams.catalog(actors.from(a), w);
  }

  @GetMapping("/workspaces/{w}/embedding-connection")
  public Object connection(Principal a, @PathVariable UUID w) {
    return teams.getConnection(actors.from(a), w);
  }

  @PutMapping("/workspaces/{w}/embedding-connection")
  public Object connection(
      Principal a, @PathVariable UUID w, @RequestBody TeamEmbeddings.ConnectionUpdate u) {
    return teams.updateConnection(actors.from(a), w, u);
  }

  @GetMapping("/workspaces/{w}/embedding-spaces")
  public Object spaces(Principal a, @PathVariable UUID w) {
    return teams.spaces(actors.from(a), w);
  }

  @GetMapping("/projects/{p}/embedding-policy")
  public Object policy(Principal a, @PathVariable UUID p) {
    return teams.policy(actors.from(a), p);
  }

  @PutMapping("/projects/{p}/embedding-policy")
  public Object policy(
      Principal a, @PathVariable UUID p, @RequestBody TeamEmbeddings.PolicyUpdate u) {
    return teams.updatePolicy(actors.from(a), p, u);
  }

  @PostMapping("/projects/{p}/knowledge/embedding-rebuild")
  public Object rebuild(Principal a, @PathVariable UUID p, @RequestBody TeamEmbeddings.Version u) {
    return teams.rebuild(actors.from(a), p, u);
  }

  @PostMapping("/projects/{p}/knowledge/embedding-activate")
  public Object activate(Principal a, @PathVariable UUID p, @RequestBody TeamEmbeddings.Version u) {
    return teams.activate(actors.from(a), p, u);
  }
}
