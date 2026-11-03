package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "workhub.retrieval.enabled", havingValue = "true")
public class KnowledgeService {
  private final ProjectAccess access;
  private final KnowledgeReads reads;
  private final TextIndex index;
  private final JdbcTemplate db;
  private final SourceText texts;
  private final SourceObligations sources;
  private final BusinessJobs jobs;
  private final Json json;
  private final OperationReceipts receipts;

  public KnowledgeService(
      ProjectAccess a,
      KnowledgeReads r,
      TextIndex i,
      JdbcTemplate d,
      SourceText t,
      SourceObligations s,
      BusinessJobs j,
      Json json,
      OperationReceipts receipts) {
    access = a;
    reads = r;
    index = i;
    db = d;
    texts = t;
    sources = s;
    jobs = j;
    this.json = json;
    this.receipts = receipts;
  }

  public record Search(
      List<KnowledgeReads.Evidence> results,
      int configId,
      String strategy,
      boolean retrievalComplete,
      int droppedStale,
      int fetchedCandidates,
      long totalCandidates,
      long indexLag,
      Instant observedAt) {}

  public Search search(ActorContext actor, UUID project, String query, int limit) {
    access.read(actor, project);
    Inputs.text(query, "q", 500);
    if (limit < 1 || limit > 20) throw ApiException.invalid("limit must be 1..20");
    if (TextChunks.terms(query).isBlank()) throw ApiException.invalid("请输入技术标识符、中文或英文查询");
    TextIndex.Candidates candidates = null;
    KnowledgeReads.Hydrated hydrated = null;
    try {
      for (int size : List.of(50, 100, 200)) {
        candidates = index.search(project, query, size);
        hydrated = reads.hydrate(actor, project, candidates.hits(), limit);
        if (hydrated.results().size() >= limit || candidates.hits().size() < size) break;
      }
    } catch (WorkFailure e) {
      throw new ApiException(503, "SEARCH_UNAVAILABLE", "知识索引暂不可用，请重试或由维护者回填重建");
    }
    var status = reads.status(actor, project);
    reads.validate(actor, project, hydrated.results());
    boolean complete =
        candidates.total() <= candidates.hits().size() || hydrated.results().size() >= limit;
    long lag = status.eligible() - status.published();
    return new Search(
        hydrated.results(),
        1,
        "BM25",
        complete && lag == 0 && (hydrated.dropped() == 0 || hydrated.results().size() >= limit),
        hydrated.dropped(),
        candidates.hits().size(),
        candidates.total(),
        lag,
        hydrated.observedAt());
  }

  public record RebuildRequest(String cursor, Integer limit) {}

  public record Rebuild(List<UUID> jobIds, String nextCursor, int owners) {}

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
  public Rebuild rebuild(ActorContext actor, UUID project, RebuildRequest request, String key) {
    access.lock(actor, project);
    access.maintainer(actor, project);
    Inputs.text(key, "Idempotency-Key", 128);
    int limit = request.limit() == null ? 50 : request.limit();
    if (limit < 1 || limit > 100) throw ApiException.invalid("limit must be 1..100");
    String kind = "";
    UUID after = new UUID(0, 0);
    if (request.cursor() != null) {
      try {
        var p = request.cursor().split(":");
        kind = p[0];
        after = UUID.fromString(p[1]);
        if (p.length != 2
            || !List.of("TASK", "DOCUMENT", "COMMENT", "GITHUB_COMMENT").contains(kind))
          throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        throw ApiException.invalid("Invalid rebuild cursor");
      }
    }
    String hash = Inputs.hash(json.write(List.of(project, request)));
    UUID previous = receipts.find(actor.userId(), key, hash);
    if (previous != null)
      return json.read(
          db.queryForObject(
              "SELECT result FROM knowledge_rebuild_batch WHERE id=? AND project_id=?",
              String.class,
              bytes(previous),
              bytes(project)),
          Rebuild.class);
    var owners =
        db.queryForList(
            "SELECT * FROM (SELECT 'TASK' kind,id FROM task WHERE project_id=? UNION ALL SELECT"
                + " 'DOCUMENT',id FROM project_document WHERE project_id=? UNION ALL SELECT"
                + " 'COMMENT',id FROM comment WHERE project_id=? UNION ALL SELECT"
                + " 'GITHUB_COMMENT',id FROM github_comment WHERE project_id=?) o WHERE kind>? OR"
                + " (kind=? AND id>?) ORDER BY kind,id LIMIT ?",
            bytes(project),
            bytes(project),
            bytes(project),
            bytes(project),
            kind,
            kind,
            bytes(after),
            limit + 1);
    boolean more = owners.size() > limit;
    if (more) owners = owners.subList(0, limit);
    List<UUID> ids = new ArrayList<>();
    for (var row : owners) {
      var type = (String) row.get("kind");
      var owner = texts.owner(project, type, uuid((byte[]) row.get("id")));
      String column =
          type.equals("TASK")
              ? "task_id"
              : type.equals("DOCUMENT")
                  ? "document_id"
                  : type.equals("COMMENT") ? "comment_id" : "github_comment_id";
      var sourceIds =
          db.query(
              "SELECT id FROM knowledge_source WHERE project_id=? AND " + column + "=?",
              (r, n) -> read(r, "id"),
              bytes(project),
              bytes(owner.id()));
      if (sourceIds.isEmpty()) {
        if (!owner.visible()) continue;
        sources.changed(
            project, owner.id(), SourceObligations.Owner.valueOf(type), owner.text(), owner.url());
        sourceIds =
            db.query(
                "SELECT id FROM knowledge_source WHERE project_id=? AND " + column + "=?",
                (r, n) -> read(r, "id"),
                bytes(project),
                bytes(owner.id()));
      }
      UUID sourceId = sourceIds.get(0);
      var source = texts.load(project, sourceId);
      if (!owner.visible()) {
        sources.deactivate(project, owner.id(), SourceObligations.Owner.valueOf(type));
        continue;
      }
      if (!source.hash().equals(Inputs.hash(owner.text()))) {
        sources.changed(
            project, owner.id(), SourceObligations.Owner.valueOf(type), owner.text(), owner.url());
        source = texts.load(project, sourceId);
      }
      db.update("UPDATE knowledge_source SET active=TRUE WHERE id=?", bytes(sourceId));
      ids.add(
          jobs.enqueue(
              "INDEX_TEXT",
              project,
              sourceId,
              "text-repair/"
                  + sourceId
                  + "/"
                  + hash
                  + "/"
                  + Inputs.hash(key)
                  + "/"
                  + source.revision(),
              Map.of("sourceId", sourceId, "sourceRevision", source.revision())));
    }
    String next =
        more
            ? owners.get(owners.size() - 1).get("kind")
                + ":"
                + uuid((byte[]) owners.get(owners.size() - 1).get("id"))
            : null;
    var result = new Rebuild(List.copyOf(ids), next, owners.size());
    UUID receipt = UUID.randomUUID();
    db.update(
        "INSERT INTO knowledge_rebuild_batch(id,project_id,result) VALUES (?,?,?)",
        bytes(receipt),
        bytes(project),
        json.write(result));
    receipts.save(actor.userId(), key, project, hash, receipt);
    return result;
  }
}
