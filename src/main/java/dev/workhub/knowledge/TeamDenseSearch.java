package dev.workhub.knowledge;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.WorkFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression(
    "${workhub.embedding.enabled:false} && !${workhub.embedding.legacy-mode:false} &&"
        + " ${workhub.retrieval.enabled:false}")
public class TeamDenseSearch {
  private final TeamEmbeddings teams;
  private final TeamEmbeddingCalls model;
  private final TeamVectorIndexes indexes;
  private final ProjectAccess access;
  private final KnowledgeService lexical;
  private final KnowledgeReads reads;

  public TeamDenseSearch(
      TeamEmbeddings teams,
      TeamEmbeddingCalls model,
      TeamVectorIndexes indexes,
      ProjectAccess access,
      KnowledgeService lexical,
      KnowledgeReads reads) {
    this.teams = teams;
    this.model = model;
    this.indexes = indexes;
    this.access = access;
    this.lexical = lexical;
    this.reads = reads;
  }

  private DenseSearch.Search fallback(
      ActorContext a,
      UUID p,
      String q,
      int limit,
      String requested,
      String reason,
      long start,
      UUID execution) {
    if (reads.status(a, p).eligible() == 0)
      return new DenseSearch.Search(
          List.of(),
          1,
          "BM25",
          requested,
          false,
          0,
          0,
          0,
          0,
          Instant.now(),
          reason,
          0,
          (System.nanoTime() - start) / 1_000_000,
          execution);
    var r = lexical.search(a, p, q, limit);
    return new DenseSearch.Search(
        r.results(),
        1,
        "BM25",
        requested,
        false,
        r.droppedStale(),
        r.fetchedCandidates(),
        r.totalCandidates(),
        r.indexLag(),
        r.observedAt(),
        reason,
        0,
        (System.nanoTime() - start) / 1_000_000,
        execution);
  }

  public DenseSearch.Search search(
      ActorContext actor, UUID p, String q, int limit, String requested) {
    return search(actor, p, q, limit, requested, () -> {});
  }

  public DenseSearch.Search search(
      ActorContext actor, UUID p, String q, int limit, String requested, Runnable executionFence) {
    access.read(actor, p);
    Inputs.text(q, "q", 500);
    if (limit < 1 || limit > 20 || !Set.of("DENSE", "HYBRID", "BM25").contains(requested))
      throw ApiException.invalid("Invalid retrieval strategy or limit");
    long start = System.nanoTime();
    UUID execution = UUID.randomUUID();
    Integer config = teams.active(p);
    if (config == null || requested.equals("BM25"))
      return fallback(
          actor,
          p,
          q,
          limit,
          requested,
          config == null ? "EMBEDDING_INDEX_NOT_ACTIVE" : null,
          start,
          null);
    if (reads.status(actor, p, config).eligible() == 0)
      return new DenseSearch.Search(
          List.of(),
          config,
          requested,
          requested,
          true,
          0,
          0,
          0,
          0,
          Instant.now(),
          null,
          0,
          (System.nanoTime() - start) / 1_000_000,
          null);
    try {
      executionFence.run();
      var ticket = teams.ticketForActor(p, config, actor);
      var space = teams.projectSpace(p, config);
      byte[] vector = model.embed(ticket, execution, "query", q, executionFence);
      executionFence.run();
      access.read(actor, p);
      teams.verify(ticket);
      if (!Objects.equals(config, teams.active(p)))
        return fallback(actor, p, q, limit, requested, "EMBEDDING_INDEX_CHANGED", start, execution);
      var candidates = indexes.search(space, p, vector, 200);
      var dense = reads.hydrate(actor, p, candidates.hits(), 200, config);
      List<KnowledgeReads.Evidence> results = dense.results().stream().limit(limit).toList();
      int fetched = candidates.hits().size(), dropped = dense.dropped();
      long total = candidates.total();
      if (requested.equals("HYBRID")) {
        var k = indexes.lexical(space, p, q, 200);
        var h = reads.hydrate(actor, p, k.hits(), 200, config);
        results = ReciprocalRanks.fuse(h.results(), dense.results(), limit);
        fetched += k.hits().size();
        dropped += h.dropped();
        total += k.total();
      }
      reads.validate(actor, p, results, config);
      access.read(actor, p);
      teams.verify(ticket);
      if (!Objects.equals(config, teams.active(p)))
        return fallback(actor, p, q, limit, requested, "EMBEDDING_INDEX_CHANGED", start, execution);
      long coverage = reads.vectorCoverage(actor, p, config),
          lag = Math.max(0, reads.status(actor, p, config).eligible() - coverage);
      return new DenseSearch.Search(
          results,
          config,
          requested,
          requested,
          lag == 0
              && (total <= fetched || results.size() >= limit)
              && (dropped == 0 || results.size() >= limit),
          dropped,
          fetched,
          total,
          lag,
          Instant.now(),
          null,
          coverage,
          (System.nanoTime() - start) / 1_000_000,
          execution);
    } catch (ApiException e) {
      if (e.status == 403) throw e;
      return fallback(actor, p, q, limit, requested, e.code, start, execution);
    } catch (WorkFailure e) {
      return fallback(actor, p, q, limit, requested, e.getMessage(), start, execution);
    }
  }
}
