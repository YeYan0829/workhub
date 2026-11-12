package dev.workhub.knowledge;

import com.fasterxml.jackson.databind.*;
import dev.workhub.messaging.WorkFailure;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${workhub.embedding.enabled:false} && ${workhub.embedding.legacy-mode:false}")
public class CloudEmbeddings {
  private final DenseConfiguration c;
  private final EmbeddingBudget budget;
  private final ObjectMapper mapper;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public CloudEmbeddings(DenseConfiguration c, EmbeddingBudget budget, ObjectMapper mapper) {
    this.c = c;
    this.budget = budget;
    this.mapper = mapper;
  }

  public byte[] embed(UUID project, String purpose, String text) {
    if (!Set.of("query", "document").contains(purpose) || text.isBlank())
      throw new WorkFailure(false, "INVALID_EMBEDDING_INPUT");
    String sent =
        c.protocol.equals("openai") && purpose.equals("query") ? c.instruction + "\n" + text : text;
    int upper =
        sent.getBytes(StandardCharsets.UTF_8).length
            + c.instruction.getBytes(StandardCharsets.UTF_8).length
            + 128;
    if (upper > 8192) throw new WorkFailure(false, "EMBEDDING_INPUT_LIMIT");
    UUID call = budget.reserve(project, purpose, sent, upper);
    long start = System.nanoTime();
    Long tokens = null;
    try {
      Map<String, Object> payload;
      if (c.protocol.equals("dashscope")) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("dimension", c.dimensions);
        parameters.put("text_type", purpose);
        if (purpose.equals("query") && !c.instruction.isBlank())
          parameters.put("instruct", c.instruction);
        payload =
            Map.of(
                "model",
                c.model,
                "input",
                Map.of("texts", List.of(text)),
                "parameters",
                parameters);
      } else
        payload =
            Map.of(
                "model",
                c.model,
                "input",
                List.of(sent),
                "dimensions",
                c.dimensions,
                "encoding_format",
                "float");
      var request =
          HttpRequest.newBuilder(URI.create(c.endpoint))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + c.key)
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new WorkFailure(
            response.statusCode() == 429 || response.statusCode() >= 500,
            "EMBEDDING_HTTP_" + response.statusCode());
      JsonNode root;
      try {
        root = mapper.readTree(response.body());
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new WorkFailure(false, "EMBEDDING_INVALID_RESPONSE");
      }
      if (root == null || !root.isObject())
        throw new WorkFailure(false, "EMBEDDING_INVALID_RESPONSE");
      if (root.path("usage").path("total_tokens").isIntegralNumber())
        tokens = root.path("usage").path("total_tokens").longValue();
      if (tokens != null && (tokens < 0 || tokens > upper))
        throw new WorkFailure(false, "EMBEDDING_USAGE_OUT_OF_BOUNDS");
      var rows =
          c.protocol.equals("dashscope")
              ? root.path("output").path("embeddings")
              : root.path("data");
      String ordinal = c.protocol.equals("dashscope") ? "text_index" : "index";
      if (!rows.isArray()
          || rows.size() != 1
          || !rows.get(0).path(ordinal).isIntegralNumber()
          || rows.get(0).path(ordinal).intValue() != 0)
        throw new WorkFailure(false, "EMBEDDING_INVALID_RESPONSE");
      var array = rows.get(0).path("embedding");
      if (!array.isArray() || array.size() != c.dimensions)
        throw new WorkFailure(false, "EMBEDDING_DIMENSION_MISMATCH");
      float[] vector = new float[c.dimensions];
      for (int i = 0; i < vector.length; i++) {
        if (!array.get(i).isNumber()) throw new WorkFailure(false, "EMBEDDING_INVALID_VALUE");
        vector[i] = array.get(i).floatValue();
      }
      byte[] result = VectorBytes.encode(vector, c.dimensions);
      budget.finish(call, tokens, (System.nanoTime() - start) / 1_000_000, null);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      budget.finish(call, tokens, (System.nanoTime() - start) / 1_000_000, "EMBEDDING_INTERRUPTED");
      throw new WorkFailure(true, "EMBEDDING_INTERRUPTED");
    } catch (java.io.IOException e) {
      budget.finish(call, tokens, (System.nanoTime() - start) / 1_000_000, "EMBEDDING_IO");
      throw new WorkFailure(true, "EMBEDDING_IO");
    } catch (WorkFailure e) {
      budget.finish(call, tokens, (System.nanoTime() - start) / 1_000_000, e.getMessage());
      throw e;
    }
  }
}
