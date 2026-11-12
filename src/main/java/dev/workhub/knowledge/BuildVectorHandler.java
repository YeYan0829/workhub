package dev.workhub.knowledge;

import dev.workhub.common.*;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${workhub.embedding.enabled:false} && ${workhub.embedding.legacy-mode:false}")
public class BuildVectorHandler implements WorkHandler {
  private final DensePipeline pipeline;
  private final TextGenerations generations;
  private final BusinessJobs jobs;
  private final Json json;

  public BuildVectorHandler(DensePipeline p, TextGenerations g, BusinessJobs j, Json json) {
    pipeline = p;
    generations = g;
    jobs = j;
    this.json = json;
  }

  public String kind() {
    return "BUILD_VECTOR";
  }

  public void lockBusiness(WorkJobRepository.Job job) {
    generations.lock(job.project(), job.owner());
  }

  public Result execute(WorkJobRepository.Job job) {
    var p = json.read(job.payload(), Map.class);
    if (p.size() != 2 || ((Number) p.get("configId")).intValue() != pipeline.c.id)
      throw new WorkFailure(false, "VECTOR_CONFIG_MISMATCH");
    UUID text = UUID.fromString(p.get("generationId").toString());
    String result;
    if (!pipeline.eligible(job.project(), job.owner(), text))
      result = "vector/obsolete/" + job.id();
    else {
      UUID generation = generations.buildVector(job, text, pipeline.c);
      jobs.enqueue(
          "PUBLISH_TEXT",
          job.project(),
          job.owner(),
          "publish-vector/" + generation,
          Map.of("generationId", generation));
      result = "vector/generation/" + generation;
    }
    return new Result(result, Inputs.hash(result));
  }
}
