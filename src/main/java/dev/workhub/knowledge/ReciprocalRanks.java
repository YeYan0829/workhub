package dev.workhub.knowledge;

import java.util.*;

/** Input ranks must already be permission/version validated and deduplicated by source. */
public final class ReciprocalRanks {
  private ReciprocalRanks() {}

  public static List<KnowledgeReads.Evidence> fuse(
      List<KnowledgeReads.Evidence> lexical, List<KnowledgeReads.Evidence> dense, int limit) {
    Map<UUID, Double> scores = new HashMap<>();
    Map<UUID, KnowledgeReads.Evidence> evidence = new HashMap<>();
    for (var ranking : List.of(lexical, dense)) {
      Set<UUID> seen = new HashSet<>();
      int rank = 0;
      for (var e : ranking) {
        if (!seen.add(e.sourceId())) continue;
        rank++;
        scores.merge(e.sourceId(), 1.0 / (60 + rank), Double::sum);
        evidence.putIfAbsent(e.sourceId(), e);
      }
    }
    return scores.keySet().stream()
        .sorted(
            Comparator.<UUID>comparingDouble(scores::get).reversed().thenComparing(UUID::toString))
        .limit(limit)
        .map(
            id -> {
              var e = evidence.get(id);
              return new KnowledgeReads.Evidence(
                  e.sourceId(),
                  e.ownerId(),
                  e.kind(),
                  e.title(),
                  e.sourceRevision(),
                  e.sourceHash(),
                  e.generationId(),
                  e.ordinal(),
                  e.text(),
                  e.canonicalUrl(),
                  e.charStart(),
                  e.charEnd(),
                  scores.get(id));
            })
        .toList();
  }
}
