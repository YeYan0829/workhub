package dev.workhub.knowledge;

import dev.workhub.common.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Immutable embedding space identity. Credentials and budget are deliberately separate. */
@Component
@DependsOn("flywayInitializer")
@ConditionalOnProperty(name = "workhub.embedding.enabled", havingValue = "true")
public class DenseConfiguration {
  public final int id, dimensions;
  public final String model, protocol, endpoint, key, index, instruction, runId, identity;
  public final boolean paidApproved, fixture;
  public final long limitNanoUsd, priceNanoUsdPerToken;

  public DenseConfiguration(
      JdbcTemplate db,
      Json json,
      @Value("${workhub.embedding.config-id:2}") int id,
      @Value("${workhub.embedding.dimensions:1024}") int dimensions,
      @Value("${workhub.embedding.model:text-embedding-v4}") String model,
      @Value("${workhub.embedding.protocol:dashscope}") String protocol,
      @Value("${workhub.embedding.endpoint:}") String endpoint,
      @Value("${workhub.embedding.api-key:}") String key,
      @Value("${workhub.embedding.index:workhub-vector-v2}") String index,
      @Value(
              "${workhub.embedding.query-instruction:Given a software project question, retrieve"
                  + " relevant issue reports and project documents.}")
          String instruction,
      @Value("${workhub.embedding.paid-approved:false}") boolean approved,
      @Value("${workhub.embedding.run-id:}") String run,
      @Value("${workhub.embedding.budget-usd:1}") BigDecimal budget,
      @Value("${workhub.embedding.price-nanousd-per-token:72}") long price) {
    this.id = id;
    this.dimensions = dimensions;
    this.model = model;
    this.protocol = protocol;
    this.endpoint = endpoint;
    this.key = key;
    this.index = index;
    this.instruction = instruction;
    paidApproved = approved;
    runId = run;
    URI uri = URI.create(endpoint);
    fixture =
        Set.of("127.0.0.1", "localhost", "::1", "[::1]")
            .contains(Objects.toString(uri.getHost(), ""));
    if (id <= 1
        || dimensions < 1
        || dimensions > 4096
        || model.isBlank()
        || key.isBlank()
        || !Set.of("dashscope", "openai").contains(protocol)
        || !index.matches("[a-z][a-z0-9_-]{0,100}")
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || fixture && "http".equals(uri.getScheme()))
        || run.isBlank()
        || run.length() > 100
        || price < 0
        || budget.signum() < 0)
      throw new IllegalArgumentException(
          "Invalid embedding configuration (credentials are never logged)");
    if (!fixture && price == 0)
      throw new IllegalArgumentException("Real provider requires conservative nonzero price");
    priceNanoUsdPerToken = fixture ? 0 : price;
    limitNanoUsd = budget.multiply(BigDecimal.valueOf(1_000_000_000L)).longValueExact();
    var contract = new TreeMap<String, Object>();
    contract.put("providerEndpoint", endpoint);
    contract.put("protocol", protocol);
    contract.put("model", model);
    contract.put("dimensions", dimensions);
    contract.put("queryInstruction", instruction);
    contract.put("documentInput", "exact-canonical-chunk-v1");
    contract.put("encoding", "l2-float32-le-v1");
    contract.put("vectorSource", "original-v1");
    contract.put("chunker", "lines-2000-utf16-overlap160-v1");
    contract.put("analyzer", "identifier-cjk-bigram-v1");
    identity = Inputs.hash(json.write(contract));
    db.update(
        "INSERT IGNORE INTO retrieval_config(id,chunker,analyzer,identity_hash,model_contract)"
            + " VALUES (?,'lines-2000-utf16-overlap160-v1','identifier-cjk-bigram-v1',?,?)",
        id,
        identity,
        json.write(contract));
    if (!identity.equals(
        db.queryForObject(
            "SELECT identity_hash FROM retrieval_config WHERE id=?", String.class, id)))
      throw new IllegalArgumentException(
          "Embedding config id belongs to another model/space; use new config and index");
  }
}
