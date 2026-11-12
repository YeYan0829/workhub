package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** All methods run inside the existing source/project locked durable-job transaction. */
@Component
public class TeamEmbeddingPipeline {
  private final JdbcTemplate db;
  private final TeamEmbeddings teams;
  private final SourceText sources;
  private final TextGenerations generations;
  private final BusinessJobs jobs;

  public TeamEmbeddingPipeline(
      JdbcTemplate db,
      TeamEmbeddings teams,
      SourceText sources,
      TextGenerations generations,
      BusinessJobs jobs) {
    this.db = db;
    this.teams = teams;
    this.sources = sources;
    this.generations = generations;
    this.jobs = jobs;
  }

  public void fanOut(WorkJobRepository.Job job, UUID canonical) {
    for (int config : teams.routes(job.project()))
      enqueueText(job.project(), job.owner(), canonical, config, "canonical/" + canonical);
  }

  public void enqueueText(UUID p, UUID source, UUID canonical, int config, String cause) {
    jobs.enqueue(
        "TEAM_EMBED_TEXT",
        p,
        source,
        "team-embed-text/" + config + "/" + source + "/" + cause,
        Map.of("configId", config, "canonicalGenerationId", canonical));
  }

  public boolean routed(UUID p, int config) {
    return teams.routed(p, config);
  }

  public boolean eligible(UUID p, UUID source, UUID text, int config) {
    if (!teams.routed(p, config)) return false;
    teams.projectSpace(p, config);
    var g = generations.generation(text);
    var s = sources.load(p, source);
    return Arrays.equals((byte[]) g.get("project_id"), bytes(p))
        && Arrays.equals((byte[]) g.get("source_id"), bytes(source))
        && ((Number) g.get("config_id")).intValue() == config
        && "TEXT".equals(g.get("stage"))
        && s.eligible()
        && s.revision() == ((Number) g.get("source_revision")).longValue()
        && s.hash().equals(g.get("source_hash"));
  }

  public void fence(UUID p, UUID source, UUID text, int config) {
    if (!eligible(p, source, text, config))
      throw new WorkFailure(false, "EMBEDDING_SOURCE_CHANGED");
  }

  public Map<String, Object> cached(UUID source, int config, String hash) {
    var rows =
        db.queryForList(
            "SELECT * FROM embedding_artifact WHERE source_id=? AND config_id=? AND input_hash=?",
            bytes(source),
            config,
            hash);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    VectorBytes.decode(
        (byte[]) r.get("vector_bytes"),
        ((Number) r.get("dimensions")).intValue(),
        (String) r.get("vector_hash"));
    return r;
  }

  public void afterText(UUID p, UUID source, UUID text, int config) {
    if (!eligible(p, source, text, config)) return;
    for (var c : generations.chunks(text))
      if (cached(source, config, (String) c.get("body_hash")) == null)
        jobs.enqueue(
            "TEAM_EMBED_CHUNK",
            p,
            source,
            "team-embed-chunk/" + text + "/" + c.get("ordinal"),
            Map.of("configId", config, "generationId", text, "ordinal", c.get("ordinal")));
    maybeVector(p, source, text, config);
  }

  public void maybeVector(UUID p, UUID source, UUID text, int config) {
    if (!eligible(p, source, text, config) || !generations.current(p, source, text)) return;
    for (var c : generations.chunks(text))
      if (cached(source, config, (String) c.get("body_hash")) == null) return;
    jobs.enqueue(
        "TEAM_EMBED_PUBLISH",
        p,
        source,
        "team-embed-publish/" + text,
        Map.of("configId", config, "generationId", text));
  }

  public void save(UUID p, UUID source, int config, String hash, byte[] vector) {
    var s = teams.projectSpace(p, config);
    VectorBytes.decode(vector, s.dimensions(), VectorBytes.hash(vector));
    db.update(
        "INSERT IGNORE INTO"
            + " embedding_artifact(id,project_id,source_id,config_id,input_hash,dimensions,vector_bytes,vector_hash)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        bytes(UUID.randomUUID()),
        bytes(p),
        bytes(source),
        config,
        hash,
        s.dimensions(),
        vector,
        VectorBytes.hash(vector));
    cached(source, config, hash);
  }
}
