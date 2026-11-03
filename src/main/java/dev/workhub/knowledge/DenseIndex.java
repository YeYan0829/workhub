package dev.workhub.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dedicated model-space index; not a second injectable TEXT adapter. */
@Component
@ConditionalOnProperty(name = "workhub.embedding.enabled", havingValue = "true")
public class DenseIndex {
  public final DenseConfiguration config;
  private final ElasticsearchTextIndex rest;

  public DenseIndex(
      DenseConfiguration c,
      ObjectMapper mapper,
      @Value("${workhub.retrieval.es-url:http://127.0.0.1:19200}") String base) {
    config = c;
    rest = new ElasticsearchTextIndex(mapper, base, c.index, c.id, c.dimensions, c.identity);
  }

  public void publish(List<TextIndex.Document> docs) {
    rest.publish(docs);
  }

  public TextIndex.Candidates lexical(UUID project, String query, int size) {
    return rest.search(project, query, size);
  }

  public TextIndex.Candidates dense(UUID project, byte[] vector, int size) {
    return rest.denseSearch(
        project, VectorBytes.decode(vector, config.dimensions, VectorBytes.hash(vector)), size);
  }
}
