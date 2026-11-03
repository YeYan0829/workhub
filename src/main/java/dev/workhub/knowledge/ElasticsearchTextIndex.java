package dev.workhub.knowledge;

import com.fasterxml.jackson.databind.*;
import dev.workhub.messaging.WorkFailure;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Immutable REST adapter. ES supplies candidate IDs, never authoritative response text. */
@Component
@ConditionalOnProperty(name = "workhub.retrieval.enabled", havingValue = "true")
public class ElasticsearchTextIndex implements TextIndex {
  private final String base, index;
  private final int configId, dimensions;
  private final String identity;
  private final ObjectMapper mapper;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public ElasticsearchTextIndex(
      ObjectMapper mapper,
      @Value("${workhub.retrieval.es-url:http://127.0.0.1:19200}") String base,
      @Value("${workhub.retrieval.index:workhub-text-v1}") String index) {
    this(mapper, base, index, 1, 0, "");
  }

  public ElasticsearchTextIndex(
      ObjectMapper mapper,
      String base,
      String index,
      int configId,
      int dimensions,
      String identity) {
    this.configId = configId;
    this.dimensions = dimensions;
    this.identity = identity;
    if (!index.matches("[a-z][a-z0-9_-]{0,100}"))
      throw new IllegalArgumentException("Invalid retrieval index");
    this.mapper = mapper;
    this.base = base.replaceAll("/$", "");
    this.index = index;
  }

  private record Reply(int status, JsonNode body) {}

  private Reply request(String method, String path, String body, String type) {
    try {
      var req =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", type)
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build();
      var r = client.send(req, HttpResponse.BodyHandlers.ofString());
      return new Reply(
          r.statusCode(),
          r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WorkFailure(true, "ES_INTERRUPTED");
    } catch (java.io.IOException e) {
      throw new WorkFailure(true, "ES_UNAVAILABLE");
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private JsonNode tree(Object value) {
    try {
      return mapper.readTree(json(value));
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private void require(Reply r) {
    if (r.status() < 200 || r.status() >= 300)
      throw new WorkFailure(
          r.status() == 429 || r.status() >= 500 || r.status() == 404, "ES_REQUEST_FAILED");
  }

  private void ensureIndex() {
    var mapping = new LinkedHashMap<String, Object>();
    for (String f :
        List.of("project_id", "source_id", "generation_id", "source_hash", "payload_hash"))
      mapping.put(f, Map.of("type", "keyword"));
    for (String f : List.of("config_id", "source_revision", "ordinal"))
      mapping.put(f, Map.of("type", "long"));
    for (String f : List.of("title_terms", "body_terms"))
      mapping.put(f, Map.of("type", "text", "analyzer", "whitespace", "similarity", "BM25"));
    if (dimensions > 0) {
      mapping.put("stage", Map.of("type", "keyword"));
      mapping.put("vector", Map.of("type", "dense_vector", "dims", dimensions, "index", false));
    }
    var settings = new LinkedHashMap<String, Object>();
    settings.put("number_of_shards", 1);
    settings.put("number_of_replicas", 0);
    if (dimensions > 0) settings.put("index.mapping.exclude_source_vectors", false);
    var meta = new LinkedHashMap<String, Object>();
    meta.put("workhub_config", configId);
    if (dimensions > 0) {
      meta.put("model_identity", identity);
      meta.put("vector_source", "original-v1");
    }
    var r =
        request(
            "PUT",
            "/" + index,
            json(
                Map.of(
                    "settings",
                    settings,
                    "mappings",
                    Map.of("dynamic", "strict", "_meta", meta, "properties", mapping))),
            "application/json");
    if (r.status() != 200
        && !(r.status() == 400
            && r.body().at("/error/type").asText().equals("resource_already_exists_exception")))
      require(r);
    var current = request("GET", "/" + index + "/_mapping", null, "application/json");
    require(current);
    if (current.body().path(index).at("/mappings/_meta/workhub_config").asInt() != configId
        || dimensions > 0
            && (!current
                    .body()
                    .path(index)
                    .at("/mappings/_meta/model_identity")
                    .asText()
                    .equals(identity)
                || !current
                    .body()
                    .path(index)
                    .at("/mappings/_meta/vector_source")
                    .asText()
                    .equals("original-v1"))) throw new WorkFailure(false, "ES_CONFIG_MISMATCH");
  }

  public void publish(List<Document> documents) {
    ensureIndex();
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    for (int start = 0; start < documents.size(); start += 50) {
      if (System.nanoTime() > deadline) throw new WorkFailure(true, "ES_PUBLISH_DEADLINE");
      var batch = documents.subList(start, Math.min(start + 50, documents.size()));
      var wire = new StringBuilder();
      for (var d : batch)
        wire.append(json(Map.of("create", Map.of("_index", index, "_id", d.id()))))
            .append('\n')
            .append(json(d.fields()))
            .append('\n');
      var r = request("POST", "/_bulk", wire.toString(), "application/x-ndjson");
      require(r);
      var items = r.body().path("items");
      if (items.size() != batch.size()) throw new WorkFailure(true, "ES_INCOMPLETE_BULK");
      for (int i = 0; i < batch.size(); i++) {
        int status = items.get(i).path("create").path("status").asInt();
        if (status == 201) continue;
        if (status == 409) {
          var stored =
              request("GET", "/" + index + "/_doc/" + batch.get(i).id(), null, "application/json");
          require(stored);
          if (!stored.body().path("_source").equals(tree(batch.get(i).fields())))
            throw new WorkFailure(false, "ES_IMMUTABLE_CONFLICT");
        } else throw new WorkFailure(status == 429 || status >= 500, "ES_BULK_ITEM_FAILED");
      }
    }
    // Refresh is source-batch scoped, not one refresh per chunk. HTTP 200 alone is insufficient.
    var refreshed = request("POST", "/" + index + "/_refresh", null, "application/json");
    require(refreshed);
    if (refreshed.body().path("_shards").path("failed").asInt() != 0)
      throw new WorkFailure(true, "ES_REFRESH_FAILED");
  }

  public Candidates search(UUID project, String query, int size) {
    var r =
        request(
            "POST",
            "/" + index + "/_search",
            json(
                Map.of(
                    "size",
                    size,
                    "track_total_hits",
                    true,
                    "_source",
                    false,
                    "query",
                    Map.of(
                        "bool",
                        Map.of(
                            "filter",
                            List.of(
                                Map.of("term", Map.of("project_id", project.toString())),
                                Map.of("term", Map.of("config_id", configId))),
                            "must",
                            List.of(
                                Map.of(
                                    "multi_match",
                                    Map.of(
                                        "query",
                                        TextChunks.terms(query),
                                        "fields",
                                        List.of("title_terms^2", "body_terms")))))))),
            "application/json");
    return candidates(r);
  }

  private Candidates candidates(Reply r) {
    require(r);
    if (r.body().path("timed_out").asBoolean()
        || r.body().path("_shards").path("failed").asInt() > 0)
      throw new WorkFailure(true, "ES_PARTIAL_SEARCH");
    List<Hit> hits = new ArrayList<>();
    for (var h : r.body().path("hits").path("hits"))
      hits.add(new Hit(h.path("_id").asText(), h.path("_score").asDouble()));
    return new Candidates(List.copyOf(hits), r.body().at("/hits/total/value").asLong());
  }

  public Candidates denseSearch(UUID project, List<Float> vector, int size) {
    if (dimensions == 0 || vector.size() != dimensions)
      throw new WorkFailure(false, "QUERY_VECTOR_MISMATCH");
    var filter =
        List.of(
            Map.of("term", Map.of("project_id", project.toString())),
            Map.of("term", Map.of("config_id", configId)),
            Map.of("term", Map.of("stage", "VECTOR")),
            Map.of("exists", Map.of("field", "vector")));
    return candidates(
        request(
            "POST",
            "/" + index + "/_search",
            json(
                Map.of(
                    "size",
                    size,
                    "track_total_hits",
                    true,
                    "_source",
                    false,
                    "query",
                    Map.of(
                        "script_score",
                        Map.of(
                            "query",
                            Map.of("bool", Map.of("filter", filter)),
                            "script",
                            Map.of(
                                "source",
                                "cosineSimilarity(params.query_vector, 'vector') + 1.0",
                                "params",
                                Map.of("query_vector", vector)))))),
            "application/json"));
  }
}
