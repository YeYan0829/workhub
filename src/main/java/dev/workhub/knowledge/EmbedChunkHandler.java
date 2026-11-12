package dev.workhub.knowledge;

import dev.workhub.common.Json;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${workhub.embedding.enabled:false} && ${workhub.embedding.legacy-mode:false}")
public class EmbedChunkHandler implements RemoteWorkHandler {
  private final DensePipeline pipeline;
  private final TextGenerations generations;
  private final CloudEmbeddings model;
  private final Json json;

  public EmbedChunkHandler(DensePipeline p, TextGenerations g, CloudEmbeddings m, Json j) {
    pipeline = p;
    generations = g;
    model = m;
    json = j;
  }

  public String kind() {
    return "EMBED_CHUNK";
  }

  public boolean safeToRetryFailure() {
    return true;
  }

  public void lockBusiness(WorkJobRepository.Job j) {
    generations.lock(j.project(), j.owner());
  }

  public record Call(
      UUID project, UUID source, UUID generation, int ordinal, String hash, String text) {}

  public Step prepare(WorkJobRepository.Job job) {
    UUID generation;
    int ordinal;
    try {
      var payload = json.read(job.payload(), Map.class);
      if (payload.size() != 3
          || !(payload.get("configId") instanceof Number n)
          || n.intValue() != pipeline.c.id
          || !(payload.get("ordinal") instanceof Number o)) throw new IllegalArgumentException();
      generation = UUID.fromString(payload.get("generationId").toString());
      ordinal = o.intValue();
    } catch (RuntimeException e) {
      throw new WorkFailure(false, "INVALID_EMBEDDING_PAYLOAD");
    }
    if (!pipeline.eligible(job.project(), job.owner(), generation))
      return Step.done("embedding/obsolete/" + job.id());
    generations.documents(job.project(), job.owner(), generation);
    var chunks = generations.chunks(generation);
    var chunk =
        chunks.stream()
            .filter(r -> ((Number) r.get("ordinal")).intValue() == ordinal)
            .findFirst()
            .orElseThrow(() -> new WorkFailure(false, "EMBEDDING_CHUNK_MISSING"));
    String hash = (String) chunk.get("body_hash");
    if (pipeline.cached(job.owner(), hash) != null) {
      pipeline.maybeVector(job.project(), job.owner(), generation);
      return Step.done("embedding/cached/" + hash);
    }
    return Step.call(
        new Call(
            job.project(), job.owner(), generation, ordinal, hash, (String) chunk.get("body")));
  }

  public Object perform(Object request) {
    var call = (Call) request;
    return model.embed(call.project(), "document", call.text());
  }

  public Step complete(WorkJobRepository.Job job, Object request, Object response) {
    var call = (Call) request;
    if (!pipeline.eligible(job.project(), job.owner(), call.generation()))
      return Step.done("embedding/obsolete/" + job.id());
    pipeline.save(job.project(), job.owner(), call.hash(), (byte[]) response);
    pipeline.maybeVector(job.project(), job.owner(), call.generation());
    return Step.done("embedding/artifact/" + call.hash());
  }
}
