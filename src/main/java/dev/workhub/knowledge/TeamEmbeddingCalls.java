package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.messaging.WorkFailure;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every physical embedding attempt has durable raw usage. No transaction spans quota wait or IO.
 */
@Service
public class TeamEmbeddingCalls {
  private final JdbcTemplate db;
  private final TeamEmbeddings teams;
  private final ObjectMapper mapper;
  private final ModelAdmission admission;
  private final TransactionTemplate tx;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public TeamEmbeddingCalls(
      JdbcTemplate db,
      TeamEmbeddings teams,
      ObjectMapper mapper,
      ModelAdmission admission,
      PlatformTransactionManager manager) {
    this.db = db;
    this.teams = teams;
    this.mapper = mapper;
    this.admission = admission;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(3);
    tx.setIsolationLevel(2);
    tx.setTimeout(5);
  }

  private void guard(TeamEmbeddings.Ticket t, Runnable sourceFence) {
    tx.executeWithoutResult(
        s -> {
          db.queryForList("SELECT id FROM workspace WHERE id=? FOR SHARE", bytes(t.workspace()));
          db.queryForList("SELECT id FROM project WHERE id=? FOR SHARE", bytes(t.project()));
          teams.verify(t);
          sourceFence.run();
        });
  }

  private UUID begin(
      TeamEmbeddings.Ticket t,
      UUID operation,
      String purpose,
      String text,
      int bound,
      Runnable fence) {
    guard(t, fence);
    return tx.execute(
        s -> {
          db.update(
              "INSERT INTO team_embedding_operation(id,project_id,config_id) VALUES (?,?,?) ON"
                  + " DUPLICATE KEY UPDATE id=id",
              bytes(operation),
              bytes(t.project()),
              t.config());
          var op =
              db.queryForMap(
                  "SELECT * FROM team_embedding_operation WHERE id=? FOR UPDATE", bytes(operation));
          if (!Arrays.equals((byte[]) op.get("project_id"), bytes(t.project()))
              || ((Number) op.get("config_id")).intValue() != t.config())
            throw ApiException.forbidden();
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM team_embedding_call WHERE operation_id=? AND (state IN"
                      + " ('PENDING','UNKNOWN') OR state='COMPLETE' AND http_status BETWEEN 200 AND"
                      + " 299 AND error_code IS NULL)",
                  Long.class,
                  bytes(operation))
              > 0) throw new WorkFailure(false, "EMBEDDING_RESULT_UNKNOWN");
          if (((Number) op.get("calls")).intValue() >= 3)
            throw new WorkFailure(false, "EMBEDDING_CALL_LIMIT");
          db.update(
              "UPDATE team_embedding_operation SET calls=calls+1 WHERE id=?", bytes(operation));
          UUID call = UUID.randomUUID();
          db.update(
              "INSERT INTO"
                  + " team_embedding_call(id,operation_id,workspace_id,project_id,config_id,actor_id,connection_version,generation_connection_version,purpose,input_hash,input_bound)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
              bytes(call),
              bytes(operation),
              bytes(t.workspace()),
              bytes(t.project()),
              t.config(),
              t.actor() == null ? null : bytes(t.actor()),
              t.connectionVersion(),
              t.generationVersion(),
              purpose,
              Inputs.hash(text),
              bound);
          return call;
        });
  }

  private void finish(
      UUID id,
      boolean sent,
      boolean response,
      Long tokens,
      int status,
      long wait,
      long permitAt,
      long start,
      String code) {
    boolean interrupted = Thread.interrupted();
    try {
      tx.executeWithoutResult(
          s ->
              db.update(
                  "UPDATE team_embedding_call SET"
                      + " state=?,usage_state=?,input_tokens=?,http_status=?,duration_ms=?,wait_ms=?,permit_at=?,error_code=?"
                      + " WHERE id=? AND state='PENDING'",
                  !sent ? "NOT_SENT" : response ? "COMPLETE" : "UNKNOWN",
                  !sent ? "NOT_APPLICABLE" : tokens == null ? "UNKNOWN" : "KNOWN",
                  tokens,
                  status == 0 ? null : status,
                  (System.nanoTime() - start) / 1_000_000,
                  wait,
                  permitAt == 0 ? null : permitAt,
                  code,
                  bytes(id)));
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private HttpResponse<byte[]> send(HttpRequest request)
      throws java.io.IOException, InterruptedException {
    var future = http.sendAsync(request, info -> new LimitedBody());
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new HttpTimeoutException("Embedding response deadline");
    } catch (ExecutionException e) {
      throw new java.io.IOException("Embedding transport failed", e.getCause());
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    }
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var b : buffers) {
        if (bytes.size() + b.remaining() > 1_000_000) {
          subscription.cancel();
          result.completeExceptionally(new java.io.IOException("Embedding response limit"));
          return;
        }
        byte[] part = new byte[b.remaining()];
        b.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    public void onError(Throwable e) {
      result.completeExceptionally(e);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }

  public byte[] embed(
      TeamEmbeddings.Ticket ticket, UUID operation, String purpose, String text, Runnable fence) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Embedding IO cannot hold a transaction");
    if (!Set.of("query", "document").contains(purpose) || text.isBlank())
      throw new WorkFailure(false, "INVALID_EMBEDDING_INPUT");
    var space = teams.projectSpace(ticket.project(), ticket.config());
    int bound =
        text.getBytes(StandardCharsets.UTF_8).length
            + (purpose.equals("query")
                ? space.queryInstruction().getBytes(StandardCharsets.UTF_8).length
                : 0)
            + 128;
    if (bound > 8192) throw new WorkFailure(false, "EMBEDDING_INPUT_LIMIT");
    String key = teams.credential(ticket);
    var params = new LinkedHashMap<String, Object>();
    params.put("dimension", space.dimensions());
    params.put("text_type", purpose);
    if (purpose.equals("query") && !space.queryInstruction().isBlank())
      params.put("instruct", space.queryInstruction());
    String payload;
    try {
      payload =
          mapper.writeValueAsString(
              Map.of(
                  "model",
                  space.model(),
                  "input",
                  Map.of("texts", List.of(text)),
                  "parameters",
                  params));
    } catch (java.io.IOException e) {
      throw new WorkFailure(false, "EMBEDDING_ENCODING");
    }
    long deadline = System.nanoTime() + 60_000_000_000L;
    for (int retry = 0; ; retry++) {
      UUID call = begin(ticket, operation, purpose, text, bound, fence);
      long start = System.nanoTime(), wait = 0, permitAt = 0;
      boolean sent = false, response = false;
      Long tokens = null;
      ModelAdmission.Decision permit = null;
      int status = 0;
      long cooldown = 0;
      String code = null;
      try {
        var request =
            HttpRequest.newBuilder(URI.create(teams.endpoint(ticket)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        long end = Math.min(deadline, System.nanoTime() + 15_000_000_000L),
            began = System.nanoTime();
        while (true) {
          if (Thread.currentThread().isInterrupted() || System.nanoTime() >= end)
            throw new WorkFailure(false, "EMBEDDING_WAIT_TIMEOUT");
          guard(ticket, fence);
          permit = admission.acquire(ticket.quota());
          if (permit.permitted()) {
            if (permit.valid()) break;
            admission.outcome(ticket.quota(), permit, 0, 0);
            permit = null;
            continue;
          }
          if (!Set.of("RATE", "COOLDOWN").contains(permit.reason()))
            throw new WorkFailure(false, "EMBEDDING_CIRCUIT_OPEN");
          Thread.sleep(Math.min(100, Math.max(1, permit.delay())));
        }
        wait = (System.nanoTime() - began) / 1_000_000;
        // Payload, credential resolution, and every SQL check precede the final permit.
        if (!permit.valid()) throw new WorkFailure(false, "EMBEDDING_PERMIT_EXPIRED");
        permitAt = permit.at();
        sent = true;
        var r = send(request);
        status = r.statusCode();
        response = true;
        if (status < 200 || status >= 300) {
          var failure =
              new ModelHttpFailure(
                  status, r.headers().firstValue("Retry-After").orElse(null), Instant.now());
          cooldown = failure.cooldownMillis;
          code = "EMBEDDING_HTTP_" + status;
          throw new WorkFailure(failure.retryable(), code);
        }
        byte[] bytes = r.body();
        var root = mapper.readTree(bytes);
        var usage = root.path("usage").path("total_tokens");
        if (usage.isIntegralNumber() && usage.canConvertToLong() && usage.longValue() >= 0)
          tokens = usage.longValue();
        var rows = root.path("output").path("embeddings");
        if (!rows.isArray()
            || rows.size() != 1
            || !rows.get(0).path("text_index").isIntegralNumber()
            || rows.get(0).path("text_index").intValue() != 0)
          throw new WorkFailure(false, "EMBEDDING_INVALID_RESPONSE");
        var values = rows.get(0).path("embedding");
        if (!values.isArray() || values.size() != space.dimensions())
          throw new WorkFailure(false, "EMBEDDING_DIMENSION_MISMATCH");
        float[] vector = new float[space.dimensions()];
        for (int i = 0; i < vector.length; i++) {
          if (!values.get(i).isNumber()) throw new WorkFailure(false, "EMBEDDING_INVALID_VALUE");
          vector[i] = values.get(i).floatValue();
        }
        byte[] encoded = VectorBytes.encode(vector, vector.length);
        finish(call, true, true, tokens, status, wait, permitAt, start, null);
        admission.outcome(ticket.quota(), permit, status, 0);
        return encoded;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        code = "EMBEDDING_INTERRUPTED";
      } catch (ApiException e) {
        code = e.code;
      } catch (java.io.IOException e) {
        code = "EMBEDDING_IO";
        response = false;
      } catch (WorkFailure e) {
        code = e.getMessage();
      } catch (RuntimeException e) {
        code = "EMBEDDING_INVALID_RESPONSE";
      }
      finish(call, sent, response, tokens, status, wait, permitAt, start, code);
      if (permit != null && permit.permitted())
        admission.outcome(ticket.quota(), permit, status, cooldown);
      boolean retryable =
          response && (status == 429 && cooldown >= 0 || Set.of(502, 503, 504).contains(status));
      if (retry == 0
          && retryable
          && !Thread.currentThread().isInterrupted()
          && System.nanoTime() < deadline
          && cooldown <= 15000) {
        try {
          Thread.sleep(Math.max(100, cooldown));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new WorkFailure(false, "EMBEDDING_INTERRUPTED");
        }
        continue;
      }
      throw new WorkFailure(false, code == null ? "EMBEDDING_RESULT_UNKNOWN" : code);
    }
  }
}
