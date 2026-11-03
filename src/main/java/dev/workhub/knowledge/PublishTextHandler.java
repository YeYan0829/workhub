package dev.workhub.knowledge;

import dev.workhub.common.Json;
import dev.workhub.messaging.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workhub.retrieval.enabled", havingValue = "true")
public class PublishTextHandler implements RemoteWorkHandler {
  private final TextGenerations generations;
  private final TextIndex index;
  private final Json json;
  private final org.springframework.beans.factory.ObjectProvider<DensePipeline> dense;
  private final org.springframework.beans.factory.ObjectProvider<DenseIndex> vectorIndex;

  public PublishTextHandler(
      TextGenerations g,
      TextIndex i,
      Json j,
      org.springframework.beans.factory.ObjectProvider<DensePipeline> dense,
      org.springframework.beans.factory.ObjectProvider<DenseIndex> vectorIndex) {
    generations = g;
    index = i;
    json = j;
    this.dense = dense;
    this.vectorIndex = vectorIndex;
  }

  public String kind() {
    return "PUBLISH_TEXT";
  }

  public boolean safeToRetryFailure() {
    return true;
  }

  public record Call(UUID generation, List<TextIndex.Document> documents) {}

  private UUID generation(WorkJobRepository.Job j) {
    try {
      var p = json.read(j.payload(), Map.class);
      if (p.size() != 1) throw new IllegalArgumentException();
      return UUID.fromString(p.get("generationId").toString());
    } catch (RuntimeException e) {
      throw new WorkFailure(false, "INVALID_GENERATION_PAYLOAD");
    }
  }

  public void lockBusiness(WorkJobRepository.Job j) {
    generations.lock(j.project(), j.owner());
  }

  public Step prepare(WorkJobRepository.Job j) {
    UUID id = generation(j);
    if (!generations.current(j.project(), j.owner(), id)) {
      generations.state(id, "OBSOLETE", null);
      return Step.done("text/obsolete/" + id);
    }
    if (generations.wouldDowngrade(j.owner(), id)) {
      generations.state(id, "OBSOLETE", null);
      afterText(j, id);
      return Step.done("text/vector-retained/" + id);
    }
    return Step.call(new Call(id, generations.documents(j.project(), j.owner(), id)));
  }

  public Object perform(Object request) {
    var docs = ((Call) request).documents();
    int config = ((Number) docs.get(0).fields().get("config_id")).intValue();
    if (config == 1) index.publish(docs);
    else {
      var i = vectorIndex.getIfAvailable();
      if (i == null || config != i.config.id)
        throw new WorkFailure(false, "VECTOR_INDEX_UNAVAILABLE");
      i.publish(docs);
    }
    return Boolean.TRUE;
  }

  public Step complete(WorkJobRepository.Job j, Object request, Object response) {
    var c = (Call) request;
    if (!generations.current(j.project(), j.owner(), c.generation())) {
      generations.state(c.generation(), "OBSOLETE", null);
      return Step.done("text/obsolete/" + c.generation());
    }
    if (generations.wouldDowngrade(j.owner(), c.generation()))
      generations.state(c.generation(), "OBSOLETE", null);
    else generations.activate(j.owner(), c.generation());
    afterText(j, c.generation());
    return Step.done("text/published/" + c.generation());
  }

  public void terminalFailure(WorkJobRepository.Job j, String code) {
    generations.state(generation(j), "FAILED", code);
  }

  private void afterText(WorkJobRepository.Job job, UUID id) {
    var p = dense.getIfAvailable();
    if (p != null
        && ((Number) generations.generation(id).get("config_id")).intValue() == p.c.id
        && "TEXT".equals(generations.generation(id).get("stage")))
      p.afterText(job.project(), job.owner(), id);
  }
}
