package dev.workhub.agent;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
public class ConversationController {
  private final ConversationService conversations; private final CurrentActor actors; private final AgentRuns runs;
  public ConversationController(ConversationService conversations,CurrentActor actors,AgentRuns runs){this.conversations=conversations;this.actors=actors;this.runs=runs;}
  @GetMapping("/projects/{p}/agent/availability")
  public Object availability(Principal u,@PathVariable UUID p){return runs.availability(actors.from(u),p);}
  @GetMapping("/me/agent/conversations")
  public Object list(Principal u,@RequestParam(required=false) UUID projectId,@RequestParam(defaultValue="false") boolean archived,
      @RequestParam(defaultValue="") String q,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return conversations.list(actors.from(u),projectId,archived,q,cursor,limit);}
  @GetMapping("/me/agent/conversations/unavailable")
  public Object unavailable(Principal u){return conversations.unavailable(actors.from(u));}
  @PostMapping("/me/agent/conversations/import-legacy")
  public Object importLegacy(Principal u){return Map.of("imported",conversations.importLegacy(actors.from(u)));}
  @PostMapping("/projects/{p}/agent/conversations") @ResponseStatus(HttpStatus.CREATED)
  public Object create(Principal u,@PathVariable UUID p){return conversations.create(actors.from(u),p);}
  @GetMapping("/agent/conversations/{c}")
  public Object get(Principal u,@PathVariable UUID c){return conversations.get(actors.from(u),c);}
  @GetMapping("/agent/conversations/{c}/messages")
  public Object messages(Principal u,@PathVariable UUID c,@RequestParam(required=false) Long before,@RequestParam(defaultValue="20") int limit){return conversations.messages(actors.from(u),c,before,limit);}
  @PatchMapping("/agent/conversations/{c}")
  public Object update(Principal u,@PathVariable UUID c,@RequestBody ConversationService.Edit body){return conversations.edit(actors.from(u),c,body);}
  @DeleteMapping("/agent/conversations/{c}")
  public Object delete(Principal u,@PathVariable UUID c){return conversations.delete(actors.from(u),c);}
  @PostMapping("/agent/conversations/{c}/turns") @ResponseStatus(HttpStatus.ACCEPTED)
  public Object submit(Principal u,@PathVariable UUID c,@RequestBody ConversationService.Submit body,@RequestHeader("Idempotency-Key") String key){return conversations.submit(actors.from(u),c,body,key,null);}
  @PostMapping("/agent/conversations/{c}/turns/{t}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
  public Object retry(Principal u,@PathVariable UUID c,@PathVariable UUID t,@RequestBody ConversationService.Submit body,@RequestHeader("Idempotency-Key") String key){return conversations.submit(actors.from(u),c,body,key,t);}
  @PostMapping("/agent/conversations/{c}/turns/{t}/cancel")
  public Object cancel(Principal u,@PathVariable UUID c,@PathVariable UUID t){return conversations.cancel(actors.from(u),c,t);}
  @PostMapping("/projects/{p}/agent/runs/{r}/conversation")
  public Object migrate(Principal u,@PathVariable UUID p,@PathVariable UUID r){return conversations.migrate(actors.from(u),p,r);}
}
