package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TextGenerations {
  private final JdbcTemplate db;
  private final Json json;
  private final SourceText sources;

  public TextGenerations(JdbcTemplate db, Json json, SourceText sources) {
    this.db = db;
    this.json = json;
    this.sources = sources;
  }

  public void lock(UUID project, UUID source) {
    db.queryForList(
        "SELECT w.id FROM workspace w JOIN project p ON p.workspace_id=w.id WHERE p.id=? FOR SHARE",
        bytes(project));
    db.queryForList("SELECT id FROM project WHERE id=? FOR SHARE", bytes(project));
    db.queryForList(
        "SELECT id FROM knowledge_source WHERE project_id=? AND id=? FOR UPDATE",
        bytes(project),
        bytes(source));
    db.queryForList(
        "SELECT source_id FROM source_index_state WHERE source_id=? ORDER BY config_id FOR UPDATE",
        bytes(source));
    db.queryForList(
        "SELECT id FROM index_generation WHERE source_id=? ORDER BY config_id,generation_no"
            + " FOR UPDATE",
        bytes(source));
  }

  public UUID build(WorkJobRepository.Job job) {
    return build(job, 1, null);
  }

  public UUID build(WorkJobRepository.Job job, int config, UUID canonical) {
    var source = sources.load(job.project(), job.owner());
    var existing =
        db.query(
            "SELECT id FROM index_generation WHERE build_job_id=?",
            (r, n) -> read(r, "id"),
            bytes(job.id()));
    if (!existing.isEmpty()) return existing.get(0);
    db.update(
        "INSERT IGNORE INTO source_index_state(source_id,config_id) VALUES (?,?)",
        bytes(source.id()),
        config);
    long number =
        db.queryForObject(
            "SELECT desired_generation+1 FROM source_index_state WHERE source_id=? AND config_id=?",
            Long.class,
            bytes(source.id()),
            config);
    UUID id = UUID.randomUUID();
    var chunks =
        canonical == null
            ? TextChunks.split(source.owner().text())
            : copyCanonical(source, canonical);
    if (chunks.isEmpty()) throw new WorkFailure(false, "EMPTY_SOURCE");
    var manifest =
        chunks.stream().map(c -> List.of(c.ordinal(), c.start(), c.end(), c.hash())).toList();
    db.update(
        "INSERT INTO"
            + " index_generation(id,source_id,project_id,config_id,generation_no,source_revision,source_hash,manifest_hash,chunk_count,build_job_id,canonical_generation_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        bytes(id),
        bytes(source.id()),
        bytes(source.project()),
        config,
        number,
        source.revision(),
        source.hash(),
        Inputs.hash(json.write(manifest)),
        chunks.size(),
        bytes(job.id()),
        canonical == null ? null : bytes(canonical));
    for (var c : chunks) {
      var fields =
          fields(source, id, c.ordinal(), source.owner().title(), c.text(), config, "TEXT", null);
      db.update(
          "INSERT INTO"
              + " knowledge_chunk(generation_id,ordinal,title,body,body_hash,payload_hash,char_start,char_end)"
              + " VALUES (?,?,?,?,?,?,?,?)",
          bytes(id),
          c.ordinal(),
          source.owner().title(),
          c.text(),
          c.hash(),
          fields.get("payload_hash"),
          c.start(),
          c.end());
    }
    db.update(
        "UPDATE source_index_state SET desired_generation=? WHERE source_id=? AND config_id=?",
        number,
        bytes(source.id()),
        config);
    return id;
  }

  private Map<String, Object> fields(
      SourceText.Source source,
      UUID generation,
      int ordinal,
      String title,
      String body,
      int config,
      String stage,
      Map<String, Object> artifact) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("project_id", source.project().toString());
    f.put("source_id", source.id().toString());
    f.put("generation_id", generation.toString());
    f.put("config_id", config);
    f.put("source_revision", source.revision());
    f.put("source_hash", source.hash());
    f.put("ordinal", ordinal);
    f.put("title_terms", TextChunks.terms(title));
    f.put("body_terms", TextChunks.terms(body));
    if (config > 1) f.put("stage", stage);
    if (artifact != null)
      f.put(
          "vector",
          VectorBytes.decode(
              (byte[]) artifact.get("vector_bytes"),
              ((Number) artifact.get("dimensions")).intValue(),
              (String) artifact.get("vector_hash")));
    f.put("payload_hash", Inputs.hash(json.write(f)));
    return f;
  }

  public List<TextIndex.Document> documents(UUID project, UUID sourceId, UUID generation) {
    var source = sources.load(project, sourceId);
    var g =
        db.queryForMap(
            "SELECT * FROM index_generation WHERE id=? AND source_id=? AND project_id=?",
            bytes(generation),
            bytes(sourceId),
            bytes(project));
    // Persisted generation inputs, not current mutable source revision, define immutable ES bytes.
    source =
        new SourceText.Source(
            source.id(),
            project,
            ((Number) g.get("source_revision")).longValue(),
            (String) g.get("source_hash"),
            source.active(),
            source.owner());
    var fixed = source;
    var docs =
        db.query(
            "SELECT * FROM knowledge_chunk WHERE generation_id=? ORDER BY ordinal",
            (r, n) -> {
              var f =
                  fields(
                      fixed,
                      generation,
                      r.getInt("ordinal"),
                      r.getString("title"),
                      r.getString("body"),
                      ((Number) g.get("config_id")).intValue(),
                      (String) g.get("stage"),
                      r.getBytes("embedding_artifact_id") == null
                          ? null
                          : artifact(
                              uuid(r.getBytes("embedding_artifact_id")),
                              sourceId,
                              ((Number) g.get("config_id")).intValue(),
                              r.getString("body_hash")));
              if (!Objects.equals(f.get("payload_hash"), r.getString("payload_hash"))
                  || !Inputs.hash(r.getString("body")).equals(r.getString("body_hash")))
                throw new WorkFailure(false, "CHUNK_HASH_MISMATCH");
              return new TextIndex.Document(generation + ":" + r.getInt("ordinal"), f);
            },
            bytes(generation));
    if (docs.size() != ((Number) g.get("chunk_count")).intValue())
      throw new WorkFailure(false, "CHUNK_MANIFEST_INCOMPLETE");
    var manifest =
        db.query(
            "SELECT ordinal,char_start,char_end,body_hash FROM knowledge_chunk WHERE"
                + " generation_id=? ORDER BY ordinal",
            (r, n) ->
                List.of(
                    r.getInt("ordinal"),
                    r.getInt("char_start"),
                    r.getInt("char_end"),
                    r.getString("body_hash")),
            bytes(generation));
    if (!Inputs.hash(json.write(manifest)).equals(g.get("manifest_hash")))
      throw new WorkFailure(false, "CHUNK_MANIFEST_INCOMPLETE");
    return docs;
  }

  public boolean current(UUID project, UUID source, UUID generation) {
    var s = sources.load(project, source);
    var rows =
        db.queryForList(
            "SELECT g.* FROM index_generation g JOIN source_index_state i ON"
                + " i.source_id=g.source_id AND i.config_id=g.config_id WHERE g.id=? AND"
                + " g.source_id=? AND g.project_id=? AND g.generation_no=i.desired_generation",
            bytes(generation),
            bytes(source),
            bytes(project));
    return s.eligible()
        && !rows.isEmpty()
        && s.revision() == ((Number) rows.get(0).get("source_revision")).longValue()
        && s.hash().equals(rows.get(0).get("source_hash"));
  }

  public void state(UUID generation, String state, String error) {
    db.update(
        "UPDATE index_generation SET"
            + " state=?,error_code=?,published_at=IF(?='PUBLISHED',UTC_TIMESTAMP(6),published_at)"
            + " WHERE id=?",
        state,
        error,
        state,
        bytes(generation));
  }

  public void activate(UUID source, UUID generation) {
    int config =
        ((Number)
                db.queryForMap(
                        "SELECT config_id FROM index_generation WHERE id=?", bytes(generation))
                    .get("config_id"))
            .intValue();
    db.update(
        "UPDATE source_index_state SET active_generation_id=? WHERE source_id=? AND config_id=?",
        bytes(generation),
        bytes(source),
        config);
    state(generation, "PUBLISHED", null);
  }

  public Map<String, Object> generation(UUID id) {
    return db.queryForMap("SELECT * FROM index_generation WHERE id=?", bytes(id));
  }

  public List<Map<String, Object>> chunks(UUID id) {
    return db.queryForList(
        "SELECT * FROM knowledge_chunk WHERE generation_id=? ORDER BY ordinal", bytes(id));
  }

  private List<TextChunks.Chunk> copyCanonical(SourceText.Source source, UUID id) {
    var g = generation(id);
    if (!Arrays.equals((byte[]) g.get("source_id"), bytes(source.id()))
        || source.revision() != ((Number) g.get("source_revision")).longValue()
        || !source.hash().equals(g.get("source_hash")))
      throw new WorkFailure(false, "CANONICAL_SOURCE_MISMATCH");
    documents(source.project(), source.id(), id);
    return chunks(id).stream()
        .map(
            r ->
                new TextChunks.Chunk(
                    ((Number) r.get("ordinal")).intValue(),
                    ((Number) r.get("char_start")).intValue(),
                    ((Number) r.get("char_end")).intValue(),
                    (String) r.get("body"),
                    (String) r.get("body_hash")))
        .toList();
  }

  public Map<String, Object> artifact(UUID id, UUID source, int config, String inputHash) {
    var rows =
        db.queryForList(
            "SELECT * FROM embedding_artifact WHERE id=? AND source_id=? AND config_id=? AND"
                + " input_hash=?",
            bytes(id),
            bytes(source),
            config,
            inputHash);
    if (rows.isEmpty()) throw new WorkFailure(false, "ARTIFACT_SCOPE_MISMATCH");
    return rows.get(0);
  }

  public UUID buildVector(WorkJobRepository.Job job, UUID text, DenseConfiguration c) {
    return buildVector(job,text,c.id,c.dimensions);
  }

  public UUID buildVector(WorkJobRepository.Job job, UUID text, int config, int dimensions) {
    var source = sources.load(job.project(), job.owner());
    var tg = generation(text);
    if (((Number) tg.get("config_id")).intValue() != config || !"TEXT".equals(tg.get("stage")))
      throw new WorkFailure(false, "VECTOR_CONFIG_MISMATCH");
    copyCanonical(source, text);
    var existing =
        db.query(
            "SELECT id FROM index_generation WHERE build_job_id=?",
            (r, n) -> read(r, "id"),
            bytes(job.id()));
    if (!existing.isEmpty()) return existing.get(0);
    var chunks = chunks(text);
    Map<Integer, Map<String, Object>> artifacts = new HashMap<>();
    for (var chunk : chunks) {
      var rows =
          db.queryForList(
              "SELECT * FROM embedding_artifact WHERE source_id=? AND config_id=? AND input_hash=?",
              bytes(source.id()),
              config,
              chunk.get("body_hash"));
      if (rows.isEmpty()) throw new WorkFailure(true, "VECTOR_ARTIFACTS_INCOMPLETE");
      var a = rows.get(0);
      if (((Number) a.get("dimensions")).intValue() != dimensions)
        throw new WorkFailure(false, "ARTIFACT_DIMENSION_MISMATCH");
      VectorBytes.decode(
          (byte[]) a.get("vector_bytes"), dimensions, (String) a.get("vector_hash"));
      artifacts.put(((Number) chunk.get("ordinal")).intValue(), a);
    }
    long number =
        db.queryForObject(
            "SELECT desired_generation+1 FROM source_index_state WHERE source_id=? AND config_id=?",
            Long.class,
            bytes(source.id()),
            config);
    UUID id = UUID.randomUUID();
    // Vector integrity is included in each immutable payload hash; canonical span manifest remains
    // compatible.
    db.update(
        "INSERT INTO"
            + " index_generation(id,source_id,project_id,config_id,generation_no,source_revision,source_hash,manifest_hash,chunk_count,build_job_id,stage,canonical_generation_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,'VECTOR',?)",
        bytes(id),
        bytes(source.id()),
        bytes(source.project()),
        config,
        number,
        source.revision(),
        source.hash(),
        tg.get("manifest_hash"),
        chunks.size(),
        bytes(job.id()),
        bytes(text));
    for (var chunk : chunks) {
      int ordinal = ((Number) chunk.get("ordinal")).intValue();
      var a = artifacts.get(ordinal);
      var f =
          fields(
              source,
              id,
              ordinal,
              (String) chunk.get("title"),
              (String) chunk.get("body"),
              config,
              "VECTOR",
              a);
      db.update(
          "INSERT INTO"
              + " knowledge_chunk(generation_id,ordinal,title,body,body_hash,payload_hash,char_start,char_end,embedding_artifact_id)"
              + " VALUES (?,?,?,?,?,?,?,?,?)",
          bytes(id),
          ordinal,
          chunk.get("title"),
          chunk.get("body"),
          chunk.get("body_hash"),
          f.get("payload_hash"),
          chunk.get("char_start"),
          chunk.get("char_end"),
          a.get("id"));
    }
    db.update(
        "UPDATE source_index_state SET desired_generation=? WHERE source_id=? AND config_id=?",
        number,
        bytes(source.id()),
        config);
    return id;
  }

  public boolean wouldDowngrade(UUID source, UUID id) {
    var g = generation(id);
    if (!"TEXT".equals(g.get("stage"))) return false;
    return db.queryForObject(
            "SELECT COUNT(*) FROM source_index_state i JOIN index_generation old ON"
                + " old.id=i.active_generation_id WHERE i.source_id=? AND i.config_id=? AND"
                + " old.stage='VECTOR' AND old.source_revision=? AND old.source_hash=?",
            Integer.class,
            bytes(source),
            g.get("config_id"),
            g.get("source_revision"),
            g.get("source_hash"))
        > 0;
  }
}
