package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bounded rebuild scans. Live canonical changes fan out to active and building spaces. */
@Component
class TeamEmbeddingRebuildJob implements WorkHandler {
  final JdbcTemplate db;
  final TeamEmbeddings teams;
  final TeamEmbeddingPipeline pipeline;
  final SourceText sources;
  final TextGenerations generations;
  final BusinessJobs jobs;
  final Json json;

  TeamEmbeddingRebuildJob(
      JdbcTemplate db,
      TeamEmbeddings teams,
      TeamEmbeddingPipeline pipeline,
      SourceText sources,
      TextGenerations generations,
      BusinessJobs jobs,
      Json json) {
    this.db = db;
    this.teams = teams;
    this.pipeline = pipeline;
    this.sources = sources;
    this.generations = generations;
    this.jobs = jobs;
    this.json = json;
  }

  public String kind() {
    return "TEAM_EMBED_REBUILD";
  }

  public Result execute(WorkJobRepository.Job job) {
    var payload = json.read(job.payload(), Map.class);
    int config = ((Number) payload.get("configId")).intValue();
    if (!teams.routed(job.project(), config)) return result("obsolete/" + job.id());
    teams.projectSpace(job.project(), config);
    UUID batch = UUID.fromString((String) payload.get("batchId"));
    String after = (String) payload.get("after");
    var ids =
        db.query(
            "SELECT id FROM knowledge_source WHERE project_id=? AND id>? ORDER BY id LIMIT 100",
            (r, n) -> read(r, "id"),
            bytes(job.project()),
            bytes(after.isEmpty() ? new UUID(0, 0) : UUID.fromString(after)));
    for (UUID id : ids) {
      var s = sources.load(job.project(), id);
      if (!s.eligible() || s.owner().text().isEmpty()) continue;
      var canonical =
          db.query(
              "SELECT id FROM index_generation WHERE source_id=? AND config_id=1 AND"
                  + " source_revision=? AND source_hash=? ORDER BY generation_no DESC LIMIT 1",
              (r, n) -> read(r, "id"),
              bytes(id),
              s.revision(),
              s.hash());
      if (canonical.isEmpty())
        jobs.enqueue(
            "INDEX_TEXT",
            job.project(),
            id,
            "team-embed-canonical/" + batch + "/" + id,
            Map.of("sourceId", id, "sourceRevision", s.revision()));
      else pipeline.enqueueText(job.project(), id, canonical.get(0), config, "rebuild/" + batch);
    }
    if (ids.size() == 100)
      teams.enqueueRebuild(job.project(), config, batch, ids.get(ids.size() - 1));
    return result("rebuild/" + job.id());
  }

  static Result result(String s) {
    return new Result(s, Inputs.hash(s));
  }
}

@Component
class TeamEmbeddingTextJob implements WorkHandler {
  final Json json;
  final TeamEmbeddingPipeline pipeline;
  final TextGenerations generations;
  final SourceText sources;

  TeamEmbeddingTextJob(Json json, TeamEmbeddingPipeline p, TextGenerations g, SourceText s) {
    this.json = json;
    pipeline = p;
    generations = g;
    sources = s;
  }

  public String kind() {
    return "TEAM_EMBED_TEXT";
  }

  public void lockBusiness(WorkJobRepository.Job j) {
    generations.lock(j.project(), j.owner());
  }

  public Result execute(WorkJobRepository.Job j) {
    var p = json.read(j.payload(), Map.class);
    int config = ((Number) p.get("configId")).intValue();
    UUID canonical = UUID.fromString((String) p.get("canonicalGenerationId"));
    var cg = generations.generation(canonical);
    if (((Number) cg.get("config_id")).intValue() != 1)
      throw new WorkFailure(false, "CANONICAL_CONFIG_MISMATCH");
    if (!pipeline.routed(j.project(), config))
      return TeamEmbeddingRebuildJob.result("obsolete/" + j.id());
    var source = sources.load(j.project(), j.owner());
    if (!source.eligible()
        || !Arrays.equals((byte[]) cg.get("project_id"), bytes(j.project()))
        || !Arrays.equals((byte[]) cg.get("source_id"), bytes(j.owner()))
        || source.revision() != ((Number) cg.get("source_revision")).longValue()
        || !source.hash().equals(cg.get("source_hash")))
      return TeamEmbeddingRebuildJob.result("obsolete/" + j.id());
    UUID text = generations.build(j, config, canonical);
    pipeline.afterText(j.project(), j.owner(), text, config);
    return TeamEmbeddingRebuildJob.result("text/" + text);
  }
}

@Component
class TeamEmbeddingChunkJob implements RemoteWorkHandler {
  final TeamEmbeddingPipeline pipeline;
  final TeamEmbeddings teams;
  final TeamEmbeddingCalls calls;
  final TextGenerations generations;
  final Json json;

  record Call(
      TeamEmbeddings.Ticket ticket,
      UUID operation,
      UUID source,
      UUID generation,
      String hash,
      String text) {}

  record Error(String code) {}

  TeamEmbeddingChunkJob(
      TeamEmbeddingPipeline p, TeamEmbeddings t, TeamEmbeddingCalls c, TextGenerations g, Json j) {
    pipeline = p;
    teams = t;
    calls = c;
    generations = g;
    json = j;
  }

  public String kind() {
    return "TEAM_EMBED_CHUNK";
  }

  public boolean safeToRetryFailure() {
    return true;
  }

  public int executionLeaseSeconds() {
    return 90;
  }

  public void lockBusiness(WorkJobRepository.Job j) {
    generations.lock(j.project(), j.owner());
  }

  public Step prepare(WorkJobRepository.Job j) {
    try {
      var p = json.read(j.payload(), Map.class);
      int config = ((Number) p.get("configId")).intValue();
      UUID text = UUID.fromString((String) p.get("generationId"));
      int ordinal = ((Number) p.get("ordinal")).intValue();
      if (!pipeline.eligible(j.project(), j.owner(), text, config))
        return Step.done("obsolete/" + j.id());
      generations.documents(j.project(), j.owner(), text);
      var chunk =
          generations.chunks(text).stream()
              .filter(c -> ((Number) c.get("ordinal")).intValue() == ordinal)
              .findFirst()
              .orElseThrow();
      String hash = (String) chunk.get("body_hash");
      if (pipeline.cached(j.owner(), config, hash) != null) {
        pipeline.maybeVector(j.project(), j.owner(), text, config);
        return Step.done("cached/" + hash);
      }
      return Step.call(
          new Call(
              teams.ticket(j.project(), config, null),
              j.id(),
              j.owner(),
              text,
              hash,
              (String) chunk.get("body")));
    } catch (ApiException e) {
      return Step.call(new Error(e.code));
    } catch (WorkFailure | IllegalArgumentException | NoSuchElementException e) {
      return Step.call(new Error("INVALID_TEAM_EMBEDDING_JOB"));
    }
  }

  public Object perform(Object r) {
    if (r instanceof Error e) throw new WorkFailure(false, e.code());
    var c = (Call) r;
    return calls.embed(
        c.ticket(),
        c.operation(),
        "document",
        c.text(),
        () ->
            pipeline.fence(c.ticket().project(), c.source(), c.generation(), c.ticket().config()));
  }

  public Step complete(WorkJobRepository.Job j, Object request, Object response) {
    var c = (Call) request;
    if (!pipeline.eligible(j.project(), j.owner(), c.generation(), c.ticket().config()))
      return Step.done("obsolete/" + j.id());
    pipeline.save(j.project(), j.owner(), c.ticket().config(), c.hash(), (byte[]) response);
    pipeline.maybeVector(j.project(), j.owner(), c.generation(), c.ticket().config());
    return Step.done("artifact/" + c.hash());
  }
}

@Component
class TeamEmbeddingPublishJob implements RemoteWorkHandler {
  final TeamEmbeddingPipeline pipeline;
  final TeamEmbeddings teams;
  final TeamVectorIndexes indexes;
  final TextGenerations generations;
  final JdbcTemplate db;
  final Json json;

  record Call(TeamEmbeddings.Space space, UUID generation, List<TextIndex.Document> documents) {}

  TeamEmbeddingPublishJob(
      TeamEmbeddingPipeline p,
      TeamEmbeddings t,
      TeamVectorIndexes i,
      TextGenerations g,
      JdbcTemplate db,
      Json j) {
    pipeline = p;
    teams = t;
    indexes = i;
    generations = g;
    this.db = db;
    json = j;
  }

  public String kind() {
    return "TEAM_EMBED_PUBLISH";
  }

  public boolean safeToRetryFailure() {
    return true;
  }

  public int executionLeaseSeconds() {
    return 90;
  }

  public void lockBusiness(WorkJobRepository.Job j) {
    generations.lock(j.project(), j.owner());
  }

  public Step prepare(WorkJobRepository.Job j) {
    try {
      var p = json.read(j.payload(), Map.class);
      int config = ((Number) p.get("configId")).intValue();
      UUID text = UUID.fromString((String) p.get("generationId"));
      if (!pipeline.eligible(j.project(), j.owner(), text, config))
        return Step.done("obsolete/" + j.id());
      boolean existing =
          db.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM index_generation WHERE build_job_id=?)",
              Boolean.class,
              bytes(j.id()));
      if (!existing && !generations.current(j.project(), j.owner(), text))
        return Step.done("obsolete/" + j.id());
      var space = teams.projectSpace(j.project(), config);
      UUID vector = generations.buildVector(j, text, config, space.dimensions());
      if (!generations.current(j.project(), j.owner(), vector))
        return Step.done("obsolete/" + j.id());
      return Step.call(
          new Call(space, vector, generations.documents(j.project(), j.owner(), vector)));
    } catch (ApiException | WorkFailure | IllegalArgumentException e) {
      return Step.call(new TeamEmbeddingChunkJob.Error("VECTOR_BUILD_FAILED"));
    }
  }

  public Object perform(Object r) {
    if (r instanceof TeamEmbeddingChunkJob.Error e) throw new WorkFailure(false, e.code());
    var c = (Call) r;
    indexes.publish(c.space(), c.documents());
    return true;
  }

  public Step complete(WorkJobRepository.Job j, Object request, Object response) {
    var c = (Call) request;
    if (!teams.routed(j.project(), c.space().configId())
        || !generations.current(j.project(), j.owner(), c.generation())) {
      generations.state(c.generation(), "OBSOLETE", null);
      return Step.done("obsolete/" + j.id());
    }
    generations.activate(j.owner(), c.generation());
    return Step.done("vector/" + c.generation());
  }
}
