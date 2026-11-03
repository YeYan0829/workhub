package dev.workhub.knowledge;

import dev.workhub.identity.CurrentActor;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects/{p}/knowledge")
@ConditionalOnProperty(name = "workhub.retrieval.enabled", havingValue = "true")
public class KnowledgeController {
  private final KnowledgeService service;
  private final KnowledgeReads reads;
  private final CurrentActor actors;
  private final org.springframework.beans.factory.ObjectProvider<DenseSearch> dense;

  public KnowledgeController(
      KnowledgeService s,
      KnowledgeReads r,
      CurrentActor a,
      org.springframework.beans.factory.ObjectProvider<DenseSearch> dense) {
    service = s;
    reads = r;
    actors = a;
    this.dense = dense;
  }

  @GetMapping("/search")
  public Object search(
      Principal actor,
      @PathVariable UUID p,
      @RequestParam String q,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "BM25") String strategy) {
    if (strategy.equals("BM25")) return service.search(actors.from(actor), p, q, limit);
    var retrieval = dense.getIfAvailable();
    if (retrieval == null)
      throw new dev.workhub.common.ApiException(503, "DENSE_DISABLED", "向量检索尚未启用");
    return retrieval.search(actors.from(actor), p, q, limit, strategy);
  }

  @GetMapping("/status")
  public KnowledgeReads.Status status(Principal actor, @PathVariable UUID p) {
    return reads.status(actors.from(actor), p);
  }

  @PostMapping("/rebuild")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public KnowledgeService.Rebuild rebuild(
      Principal actor,
      @PathVariable UUID p,
      @RequestBody KnowledgeService.RebuildRequest request,
      @RequestHeader("Idempotency-Key") String key) {
    return service.rebuild(actors.from(actor), p, request, key);
  }
}
