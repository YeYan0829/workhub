package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called only inside the existing business/source-locked job transaction. */
@Component
@ConditionalOnProperty(name = "workhub.embedding.enabled", havingValue = "true")
public class DensePipeline {
  final DenseConfiguration c;
  private final JdbcTemplate db;
  private final TextGenerations generations;
  private final BusinessJobs jobs;
  private final SourceText sources;

  public DensePipeline(
      DenseConfiguration c, JdbcTemplate db, TextGenerations g, BusinessJobs j, SourceText s) {
    this.c = c;
    this.db = db;
    generations = g;
    jobs = j;
    sources = s;
  }

  public void fanOut(WorkJobRepository.Job job, UUID canonical) {
    jobs.enqueue(
        "INDEX_TEXT",
        job.project(),
        job.owner(),
        "index-config/" + c.id + "/" + canonical,
        Map.of(
            "sourceId",
            job.owner(),
            "sourceRevision",
            sources.load(job.project(), job.owner()).revision(),
            "configId",
            c.id,
            "canonicalGenerationId",
            canonical));
  }

  public boolean eligible(UUID project, UUID source, UUID text) {
    var g = generations.generation(text);
    var s = sources.load(project, source);
    return Arrays.equals((byte[]) g.get("source_id"), bytes(source))
        && Arrays.equals((byte[]) g.get("project_id"), bytes(project))
        && ((Number) g.get("config_id")).intValue() == c.id
        && "TEXT".equals(g.get("stage"))
        && s.eligible()
        && s.revision() == ((Number) g.get("source_revision")).longValue()
        && s.hash().equals(g.get("source_hash"));
  }

  public Map<String, Object> cached(UUID source, String hash) {
    var rows =
        db.queryForList(
            "SELECT * FROM embedding_artifact WHERE source_id=? AND config_id=? AND input_hash=?",
            bytes(source),
            c.id,
            hash);
    if (rows.isEmpty()) return null;
    var a = rows.get(0);
    if (((Number) a.get("dimensions")).intValue() != c.dimensions)
      throw new WorkFailure(false, "ARTIFACT_DIMENSION_MISMATCH");
    VectorBytes.decode((byte[]) a.get("vector_bytes"), c.dimensions, (String) a.get("vector_hash"));
    return a;
  }

  public void afterText(UUID project, UUID source, UUID text) {
    if (!eligible(project, source, text)) return;
    for (var chunk : generations.chunks(text)) {
      if (cached(source, (String) chunk.get("body_hash")) == null)
        jobs.enqueue(
            "EMBED_CHUNK",
            project,
            source,
            "embed/" + text + "/" + chunk.get("ordinal"),
            Map.of("generationId", text, "ordinal", chunk.get("ordinal"), "configId", c.id));
    }
    maybeVector(project, source, text);
  }

  public void maybeVector(UUID project, UUID source, UUID text) {
    if (!eligible(project, source, text)) return;
    for (var chunk : generations.chunks(text))
      if (cached(source, (String) chunk.get("body_hash")) == null) return;
    jobs.enqueue(
        "BUILD_VECTOR",
        project,
        source,
        "build-vector/" + text,
        Map.of("generationId", text, "configId", c.id));
  }

  public void save(UUID project, UUID source, String inputHash, byte[] vector) {
    // First committed artifact wins. A second model response must never overwrite it.
    VectorBytes.decode(vector, c.dimensions, VectorBytes.hash(vector));
    db.update(
        "INSERT IGNORE INTO"
            + " embedding_artifact(id,project_id,source_id,config_id,input_hash,dimensions,vector_bytes,vector_hash)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        bytes(UUID.randomUUID()),
        bytes(project),
        bytes(source),
        c.id,
        inputHash,
        c.dimensions,
        vector,
        VectorBytes.hash(vector));
    cached(source, inputHash);
  }
}
