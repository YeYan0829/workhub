package dev.workhub.knowledge;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.WorkFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("${workhub.embedding.enabled:false} && ${workhub.embedding.legacy-mode:false}")
public class DenseSearch {
  private final ProjectAccess access;
  private final KnowledgeReads reads;
  private final DenseIndex index;
  private final CloudEmbeddings model;

  public DenseSearch(ProjectAccess a, KnowledgeReads r, DenseIndex i, CloudEmbeddings m) {
    access = a;
    reads = r;
    index = i;
    model = m;
  }

  public record Search(
      List<KnowledgeReads.Evidence> results,
      int configId,
      String strategy,
      String requestedStrategy,
      boolean retrievalComplete,
      int droppedStale,
      int fetchedCandidates,
      long totalCandidates,
      long indexLag,
      Instant observedAt,
      String degradedReason,
      long vectorSources,
      long searchMillis,
      UUID executionId) {
    public Search(List<KnowledgeReads.Evidence> results,int configId,String strategy,String requestedStrategy,
        boolean retrievalComplete,int droppedStale,int fetchedCandidates,long totalCandidates,long indexLag,
        Instant observedAt,String degradedReason,long vectorSources,long searchMillis){
      this(results,configId,strategy,requestedStrategy,retrievalComplete,droppedStale,fetchedCandidates,
          totalCandidates,indexLag,observedAt,degradedReason,vectorSources,searchMillis,null);
    }
  }

  private record Lane(KnowledgeReads.Hydrated hydrated, TextIndex.Candidates candidates) {}

  public Search search(
      ActorContext actor, UUID project, String query, int limit, String requested) {
    access.read(actor, project);
    Inputs.text(query, "q", 500);
    if (limit < 1 || limit > 20 || !Set.of("BM25", "DENSE", "HYBRID").contains(requested))
      throw ApiException.invalid("Invalid retrieval strategy or limit");
    long start = System.nanoTime();
    byte[] vector = null;
    String degraded = null;
    if (!requested.equals("BM25")) {
      try {
        vector = model.embed(project, "query", query);
      } catch (WorkFailure e) {
        degraded = e.getMessage();
      }
      // No ES request/hydration after a permission revocation during remote model IO.
      access.read(actor, project);
    }
    String actual = vector == null ? "BM25" : requested;
    int config = index.config.id;
    List<KnowledgeReads.Evidence> results;
    List<Lane> lanes = new ArrayList<>();
    try {
      if (actual.equals("HYBRID")) {
        // RRF operates on current unique source rankings; each channel gets a bounded window.
        var k = lane(actor, project, query, null, 200, config);
        var v = lane(actor, project, query, vector, 200, config);
        lanes.add(k);
        lanes.add(v);
        results = ReciprocalRanks.fuse(k.hydrated.results(), v.hydrated.results(), limit);
      } else {
        Lane lane = null;
        for (int size : List.of(50, 100, 200)) {
          lane = lane(actor, project, query, actual.equals("DENSE") ? vector : null, size, config);
          if (lane.hydrated.results().size() >= limit || lane.candidates.hits().size() < size)
            break;
        }
        lanes.add(lane);
        results = lane.hydrated.results().stream().limit(limit).toList();
      }
    } catch (WorkFailure e) {
      throw new ApiException(503, "SEARCH_UNAVAILABLE", "知识索引暂不可用，请重试或重建");
    }
    var status = reads.status(actor, project, config);
    long coverage = reads.vectorCoverage(actor, project, config);
    reads.validate(actor, project, results, config);
    long total = lanes.stream().mapToLong(l -> l.candidates.total()).sum();
    int fetched = lanes.stream().mapToInt(l -> l.candidates.hits().size()).sum(),
        dropped = lanes.stream().mapToInt(l -> l.hydrated.dropped()).sum();
    long lag =
        Math.max(0, status.eligible() - (!actual.equals("BM25") ? coverage : status.published()));
    boolean exhausted =
        lanes.stream().allMatch(l -> l.candidates.total() <= l.candidates.hits().size());
    boolean complete =
        degraded == null
            && lag == 0
            && (exhausted || results.size() >= limit)
            && (dropped == 0 || results.size() >= limit);
    return new Search(
        results,
        config,
        actual,
        requested,
        complete,
        dropped,
        fetched,
        total,
        lag,
        Instant.now(),
        degraded,
        coverage,
        (System.nanoTime() - start) / 1_000_000);
  }

  private Lane lane(
      ActorContext actor, UUID project, String query, byte[] vector, int size, int config) {
    var candidates =
        vector == null ? index.lexical(project, query, size) : index.dense(project, vector, size);
    return new Lane(reads.hydrate(actor, project, candidates.hits(), size, config), candidates);
  }
}
