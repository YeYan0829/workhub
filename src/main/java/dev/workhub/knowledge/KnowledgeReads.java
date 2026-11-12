package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class KnowledgeReads {
  private final ProjectAccess access;
  private final JdbcTemplate db;
  private final SourceText sources;

  public KnowledgeReads(ProjectAccess a, JdbcTemplate d, SourceText s) {
    access = a;
    db = d;
    sources = s;
  }

  public record Evidence(
      UUID sourceId,
      UUID ownerId,
      String kind,
      String title,
      long sourceRevision,
      String sourceHash,
      UUID generationId,
      int ordinal,
      String text,
      String canonicalUrl,
      int charStart,
      int charEnd,
      double score) {}

  public record Hydrated(List<Evidence> results, int dropped, Instant observedAt) {}

  /** Canonical direct read: config 0 has no search-index generation dependency. */
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Evidence open(ActorContext actor, UUID project, String kind, UUID owner,
      Long expectedRevision, int start, int length) {
    access.read(actor,project);
    String column=switch(kind){case "TASK"->"task_id";case "DOCUMENT"->"document_id";
      case "COMMENT"->"comment_id";case "GITHUB_COMMENT"->"github_comment_id";
      default->throw ApiException.invalid("Unknown content type");};
    var ids=db.query("SELECT id FROM knowledge_source WHERE project_id=? AND "+column+"=?",
        (r,n)->read(r,"id"),bytes(project),bytes(owner));
    if(ids.isEmpty())throw ApiException.missing();
    var s=sources.load(project,ids.get(0));
    if(!s.eligible() || expectedRevision!=null && expectedRevision!=s.revision())
      throw new ApiException(409,"KNOWLEDGE_CHANGED","正文已失效或版本变化，请重新读取");
    String body=s.owner().text();
    if(start<0 || start>body.length() || length<1 || length>4000)throw ApiException.invalid("Invalid text range");
    if(start>0 && start<body.length() && Character.isLowSurrogate(body.charAt(start)))start--;
    int end=Math.min(body.length(),start+length);
    if(end<body.length() && end>start && Character.isHighSurrogate(body.charAt(end-1)))end--;
    // A one-unit request at a supplementary character must still make progress.
    // Return that complete code point (two UTF-16 units), never an empty page.
    if(end==start && start<body.length())end=Math.min(body.length(),start+2);
    return new Evidence(s.id(),owner,kind,s.owner().title(),s.revision(),s.hash(),null,
        start,body.substring(start,end),s.owner().url(),start,end,0);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public int contentLength(ActorContext actor,UUID project,UUID source) {
    access.read(actor,project);var s=sources.load(project,source);
    if(!s.eligible())throw new ApiException(409,"KNOWLEDGE_CHANGED","正文失效");
    return s.owner().text().length();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Hydrated hydrate(ActorContext actor, UUID project, List<TextIndex.Hit> hits, int limit) {
    return hydrate(actor, project, hits, limit, 1);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Hydrated hydrate(
      ActorContext actor, UUID project, List<TextIndex.Hit> hits, int limit, int config) {
    access.read(actor, project);
    if (hits.size() > 200) throw ApiException.invalid("Candidate batch exceeds 200");
    List<ChunkKey> keys = new ArrayList<>();
    Set<ChunkKey> distinct = new LinkedHashSet<>();
    for (var hit : hits) {
      ChunkKey key = null;
      try {
        var split = hit.id().split(":");
        if (split.length != 2) throw new IllegalArgumentException();
        key = new ChunkKey(UUID.fromString(split[0]), Integer.parseInt(split[1]));
      } catch (RuntimeException ignored) {
      }
      keys.add(key);
      if (key != null) distinct.add(key);
    }
    Map<ChunkKey, Map<String, Object>> rows = new HashMap<>();
    if (!distinct.isEmpty()) {
      var args = new ArrayList<Object>();
      args.add(bytes(project));
      args.add(config);
      for (var key : distinct) {
        args.add(bytes(key.generation()));
        args.add(key.ordinal());
      }
      String sql =
          "SELECT c.*,g.source_id,g.source_revision,g.source_hash FROM knowledge_chunk c JOIN"
              + " index_generation g ON g.id=c.generation_id JOIN source_index_state i ON"
              + " i.source_id=g.source_id AND i.config_id=g.config_id AND"
              + " i.active_generation_id=g.id WHERE g.project_id=? AND g.config_id=? AND"
              + " g.state='PUBLISHED' AND (c.generation_id,c.ordinal) IN ("
              + String.join(",", Collections.nCopies(distinct.size(), "(?,?)"))
              + ")";
      for (var row : db.queryForList(sql, args.toArray()))
        rows.put(
            new ChunkKey(
                uuid((byte[]) row.get("generation_id")), ((Number) row.get("ordinal")).intValue()),
            row);
    }
    Map<UUID, List<ChunkKey>> bySource = new LinkedHashMap<>();
    for (var entry : rows.entrySet())
      bySource
          .computeIfAbsent(
              uuid((byte[]) entry.getValue().get("source_id")), id -> new ArrayList<>())
          .add(entry.getKey());
    Map<ChunkKey, Evidence> checked = new HashMap<>();
    for (var batch : batches(bySource.keySet())) {
      var loaded = sources.loadMany(project, batch);
      for (var id : batch) {
        var source = loaded.get(id);
        if (source == null) continue;
        // Keep at most 32 full owner bodies per batch; no cache survives this transaction.
        String current = source.owner().text();
        if (!source.active()
            || !source.owner().visible()
            || !source.hash().equals(Inputs.hash(current))) continue;
        for (var key : bySource.get(id)) {
          var r = rows.get(key);
          String text = (String) r.get("body");
          int start = ((Number) r.get("char_start")).intValue(),
              end = ((Number) r.get("char_end")).intValue();
          if (end < start
              || source.revision() != ((Number) r.get("source_revision")).longValue()
              || !source.hash().equals(r.get("source_hash"))
              || !Inputs.hash(text).equals(r.get("body_hash"))
              || start < 0
              || end > current.length()
              || !current.substring(start, end).equals(text)) continue;
          checked.put(
              key,
              new Evidence(
                  source.id(),
                  source.owner().id(),
                  source.owner().kind(),
                  source.owner().title(),
                  source.revision(),
                  source.hash(),
                  key.generation(),
                  key.ordinal(),
                  text,
                  source.owner().url(),
                  start,
                  end,
                  0));
        }
      }
    }
    List<Evidence> results = new ArrayList<>();
    Set<UUID> unique = new HashSet<>();
    int dropped = 0;
    for (int i = 0; i < hits.size(); i++) {
      var e = checked.get(keys.get(i));
      if (e == null) {
        dropped++;
        continue;
      }
      if (unique.add(e.sourceId()) && results.size() < limit)
        results.add(
            new Evidence(
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
                hits.get(i).score()));
    }
    access.read(actor, project);
    return new Hydrated(List.copyOf(results), dropped, Instant.now());
  }

  private record ChunkKey(UUID generation, int ordinal) {}

  private static List<List<UUID>> batches(Collection<UUID> ids) {
    var list = new ArrayList<>(ids);
    List<List<UUID>> batches = new ArrayList<>();
    for (int i = 0; i < list.size(); i += 32)
      batches.add(list.subList(i, Math.min(i + 32, list.size())));
    return batches;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public void validate(ActorContext actor, UUID project, List<Evidence> results) {
    validate(actor, project, results, 1);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public void validate(ActorContext actor, UUID project, List<Evidence> results, int config) {
    access.read(actor, project);
    var ids =
        results.stream()
            .map(Evidence::sourceId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, List<Evidence>> evidence = new HashMap<>();
    for (var e : results) evidence.computeIfAbsent(e.sourceId(), id -> new ArrayList<>()).add(e);
    for (var batch : batches(ids)) {
      var loaded = sources.loadMany(project, batch);
      for (var id : batch) {
        var s = loaded.get(id);
        if (s == null || !s.eligible())
          throw new ApiException(409, "KNOWLEDGE_CHANGED", "项目资料在检索期间变化，请重试");
        for (var e : evidence.get(id))
          if (s.revision() != e.sourceRevision() || !s.hash().equals(e.sourceHash())
              || e.charStart()<0 || e.charEnd()>s.owner().text().length() || e.charEnd()<e.charStart())
            throw new ApiException(409, "KNOWLEDGE_CHANGED", "项目资料在检索期间变化，请重试");
      }
    }
    if (!ids.isEmpty() && config!=0) {
      var args = new ArrayList<Object>();
      args.add(bytes(project));
      args.add(config);
      ids.forEach(id -> args.add(bytes(id)));
      var pointers =
          db.query(
              "SELECT i.source_id,i.active_generation_id FROM source_index_state i JOIN"
                  + " knowledge_source s ON s.id=i.source_id WHERE s.project_id=? AND i.config_id=?"
                  + " AND i.source_id IN ("
                  + String.join(",", Collections.nCopies(ids.size(), "?"))
                  + ")",
              (r, n) ->
                  Map.entry(
                      read(r, "source_id"),
                      r.getBytes("active_generation_id") == null
                          ? new UUID(0, 0)
                          : read(r, "active_generation_id")),
              args.toArray());
      var active = new HashMap<UUID, UUID>();
      for (var pointer : pointers) active.put(pointer.getKey(), pointer.getValue());
      for (var e : results)
        if (!e.generationId().equals(active.get(e.sourceId())))
          throw new ApiException(409, "KNOWLEDGE_CHANGED", "索引在检索期间更新，请重试");
    }
  }

  public record Status(
      long sources, long eligible, long published, long pending, long failed, int configId) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Status status(ActorContext actor, UUID project) {
    return status(actor, project, 1);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Status status(ActorContext actor, UUID project, int config) {
    access.read(actor, project);
    long count =
        db.queryForObject(
            "SELECT COUNT(*) FROM knowledge_source WHERE project_id=?", Long.class, bytes(project));
    var counts =
        db.queryForMap(
            "SELECT COUNT(*) eligible,COALESCE(SUM(g.state='PUBLISHED' AND"
                + " g.source_revision=s.source_revision AND g.source_hash=s.body_hash),0) published"
                + " FROM knowledge_source s LEFT JOIN task t ON t.id=s.task_id LEFT JOIN"
                + " project_document d ON d.id=s.document_id LEFT JOIN comment c ON"
                + " c.id=s.comment_id LEFT JOIN github_comment x ON x.id=s.github_comment_id LEFT"
                + " JOIN source_index_state i ON i.source_id=s.id AND i.config_id=? LEFT JOIN"
                + " index_generation g ON g.id=i.active_generation_id WHERE s.project_id=? AND"
                + " s.active AND ((t.id IS NOT NULL) OR (d.id IS NOT NULL AND NOT d.deleted) OR"
                + " (c.id IS NOT NULL AND NOT c.deleted) OR (x.id IS NOT NULL AND x.active))",
            config,
            bytes(project));
    long eligible = ((Number) counts.get("eligible")).longValue(),
        published = ((Number) counts.get("published")).longValue();
    long pending =
        db.queryForObject(
            "SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler IN"
                + " ('INDEX_TEXT','PUBLISH_TEXT','EMBED_CHUNK','BUILD_VECTOR','TEAM_EMBED_REBUILD','TEAM_EMBED_TEXT','TEAM_EMBED_CHUNK','TEAM_EMBED_PUBLISH') AND status IN"
                + " ('WAITING','RETRY_WAIT','RUNNING')",
            Long.class,
            bytes(project));
    long failed =
        db.queryForObject(
            "SELECT COUNT(*) FROM work_job WHERE project_id=? AND handler IN"
                + " ('INDEX_TEXT','PUBLISH_TEXT','EMBED_CHUNK','BUILD_VECTOR','TEAM_EMBED_REBUILD','TEAM_EMBED_TEXT','TEAM_EMBED_CHUNK','TEAM_EMBED_PUBLISH') AND status='FAILED'",
            Long.class,
            bytes(project));
    return new Status(count, eligible, published, pending, failed, config);
  }

  @Transactional(readOnly = true)
  public long vectorCoverage(ActorContext actor, UUID project, int config) {
    access.read(actor, project);
    return db.queryForObject(
        "SELECT COUNT(*) FROM source_index_state i JOIN index_generation g ON"
            + " g.id=i.active_generation_id JOIN knowledge_source s ON s.id=i.source_id LEFT JOIN"
            + " task t ON t.id=s.task_id LEFT JOIN project_document d ON d.id=s.document_id LEFT"
            + " JOIN comment c ON c.id=s.comment_id LEFT JOIN github_comment x ON"
            + " x.id=s.github_comment_id WHERE s.project_id=? AND i.config_id=? AND"
            + " g.stage='VECTOR' AND g.state='PUBLISHED' AND s.active AND"
            + " g.source_revision=s.source_revision AND g.source_hash=s.body_hash AND (t.id IS NOT"
            + " NULL OR d.id IS NOT NULL AND NOT d.deleted OR c.id IS NOT NULL AND NOT c.deleted OR"
            + " x.id IS NOT NULL AND x.active)",
        Long.class,
        bytes(project),
        config);
  }
}
