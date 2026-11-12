package dev.workhub.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.common.*;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workhub.retrieval.enabled", havingValue = "true")
public class IndexTextHandler implements WorkHandler {
  @org.springframework.beans.factory.annotation.Autowired(required=false) private TeamEmbeddingPipeline teamEmbedding;
  private final TextGenerations generations;
  private final SourceText sources;
  private final BusinessJobs jobs;
  private final ObjectMapper mapper;
  private final org.springframework.beans.factory.ObjectProvider<DensePipeline> dense;

  public IndexTextHandler(
      TextGenerations g,
      SourceText s,
      BusinessJobs j,
      ObjectMapper m,
      org.springframework.beans.factory.ObjectProvider<DensePipeline> dense) {
    generations = g;
    sources = s;
    jobs = j;
    mapper = m;
    this.dense = dense;
  }

  public String kind() {
    return "INDEX_TEXT";
  }

  public void lockBusiness(WorkJobRepository.Job job) {
    generations.lock(job.project(), job.owner());
  }

  public Result execute(WorkJobRepository.Job job) {
    long requested;
    int config = 1;
    UUID canonical = null;
    try {
      var p = mapper.readTree(job.payload());
      if ((p.size() != 2 && p.size() != 4)
          || !p.path("sourceId").asText().equals(job.owner().toString())
          || !p.path("sourceRevision").isIntegralNumber())
        throw new WorkFailure(false, "INVALID_SOURCE_PAYLOAD");
      requested = p.path("sourceRevision").longValue();
      if (p.size() == 4) {
        config = p.path("configId").asInt();
        canonical = UUID.fromString(p.path("canonicalGenerationId").asText());
        var pipeline = dense.getIfAvailable();
        if (pipeline == null || config != pipeline.c.id)
          throw new WorkFailure(false, "INDEX_CONFIG_UNAVAILABLE");
      }
    } catch (java.io.IOException e) {
      throw new WorkFailure(false, "INVALID_SOURCE_PAYLOAD");
    }
    var source = sources.load(job.project(), job.owner());
    if (requested < 0 || requested > source.revision())
      throw new WorkFailure(false, "SOURCE_REVISION_AHEAD");
    String ref;
    if (requested != source.revision() || !source.active() || !source.owner().visible())
      ref = "text/obsolete/" + job.id();
    else {
      if (!source.eligible()) throw new WorkFailure(false, "SOURCE_HASH_MISMATCH");
      if (source.owner().text().isEmpty()) ref = "text/empty/" + job.id();
      else {
        UUID generation = generations.build(job, config, canonical);
        jobs.enqueue(
            "PUBLISH_TEXT",
            job.project(),
            job.owner(),
            "publish-text/" + generation,
            Map.of("generationId", generation));
        ref = "text/generation/" + generation;
        var pipeline = dense.getIfAvailable();
        if (config == 1 && pipeline != null) pipeline.fanOut(job, generation);
        if (config == 1 && teamEmbedding != null) teamEmbedding.fanOut(job,generation);
      }
    }
    return new Result(ref, Inputs.hash(ref));
  }
}
