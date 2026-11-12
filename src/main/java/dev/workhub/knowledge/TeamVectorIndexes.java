package dev.workhub.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The immutable team space selects both ES mapping and physical index. */
@Component
public class TeamVectorIndexes {
  private final ObjectMapper mapper;
  private final String base;

  public TeamVectorIndexes(
      ObjectMapper mapper,
      @Value("${workhub.retrieval.es-url:http://127.0.0.1:19200}") String base) {
    this.mapper = mapper;
    this.base = base;
  }

  private ElasticsearchTextIndex adapter(TeamEmbeddings.Space s) {
    return new ElasticsearchTextIndex(
        mapper, base, s.indexName(), s.configId(), s.dimensions(), s.identity());
  }

  public void publish(TeamEmbeddings.Space s, List<TextIndex.Document> docs) {
    adapter(s).publish(docs);
  }

  public TextIndex.Candidates search(
      TeamEmbeddings.Space s, UUID project, byte[] vector, int limit) {
    return adapter(s)
        .denseSearch(
            project, VectorBytes.decode(vector, s.dimensions(), VectorBytes.hash(vector)), limit);
  }

  public TextIndex.Candidates lexical(TeamEmbeddings.Space s, UUID project, String q, int limit) {
    return adapter(s).search(project, q, limit);
  }
}
