package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import dev.workhub.agent.*;
import dev.workhub.common.ApiException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Two real JVMs execute the production usage/send/Lua chain against one fake supplier. */
public class TeamModelMultiJvmIT {
  @Test
  void twelveRunsAcrossTwoJvmsShareOneTeamQuotaAndAttributeTwoProjects() throws Exception {
    Path dir =
        Path.of(
                "target/team-model-governance",
                LocalDate.now().toString(),
                "two-jvm-" + UUID.randomUUID())
            .toAbsolutePath();
    Files.createDirectories(dir);
    var arrivals = new CopyOnWriteArrayList<Map<String, Object>>();
    var children = new ArrayList<Process>();
    try (var http = new MockWebServer()) {
      http.setDispatcher(
          new Dispatcher() {
            double tokens = 4;
            long last = System.nanoTime();

            public synchronized MockResponse dispatch(RecordedRequest request) {
              long now = System.nanoTime();
              tokens = Math.min(4, tokens + (now - last) * 4 / 1_000_000_000d);
              last = now;
              int status = tokens >= 1 ? 200 : 429;
              if (status == 200) tokens--;
              String body = request.getBody().readUtf8();
              String run = "";
              try {
                run =
                    TeamModelFixture.JSON
                        .readTree(body)
                        .path("messages")
                        .get(0)
                        .path("content")
                        .asText()
                        .replace("fixture/", "");
              } catch (Exception ignored) {
              }
              arrivals.add(Map.of("atNanos", now, "runId", run, "status", status));
              if (status == 429)
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1");
              return new MockResponse()
                  .setBodyDelay(100, TimeUnit.MILLISECONDS)
                  .setHeader("Content-Type", "application/json")
                  .setBody(
                      "{\"id\":\"fixture\",\"model\":\"glm-4.7\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
            }
          });
      http.start();
      String endpoint = http.url("/chat/completions").toString();
      try (var f = new TeamModelFixture(endpoint, Clock.systemUTC())) {
        Flyway.configure().dataSource(f.source).load().migrate();
        var team = f.team();
        f.configure(team, 4, 2);
        var inputs = new ArrayList<String>();
        for (int i = 0; i < 12; i++)
          inputs.add(f.run(team, i % 2 == 0 ? team.first() : team.second()).toString());
        for (int i = 0; i < 2; i++) {
          TeamModelFixture.JSON.writeValue(
              dir.resolve("input-" + i + ".json").toFile(), inputs.subList(i * 6, i * 6 + 6));
          children.add(
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin/java").toString(),
                      "-Xmx192m",
                      "-XX:ActiveProcessorCount=2",
                      "-cp",
                      System.getProperty("java.class.path"),
                      TeamModelMultiJvmIT.class.getName(),
                      dir.toString(),
                      "" + i,
                      endpoint)
                  .redirectErrorStream(true)
                  .redirectOutput(dir.resolve("child-" + i + ".log").toFile())
                  .start());
        }
        TeamModelGovernanceIT.await(
            () -> Files.exists(dir.resolve("ready-0")) && Files.exists(dir.resolve("ready-1")));
        long start = System.nanoTime();
        Files.createFile(dir.resolve("go"));
        for (var child : children) {
          assertThat(child.waitFor(60, TimeUnit.SECONDS)).as(dir.toString()).isTrue();
          assertThat(child.exitValue()).as(dir.toString()).isZero();
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        var results = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2; i++)
          results.addAll(
              TeamModelFixture.JSON.readValue(
                  dir.resolve("result-" + i + ".json").toFile(), List.class));
        var facts = new LinkedHashMap<String, Object>();
        facts.put(
            "configuration",
            Map.of(
                "jvms",
                2,
                "runs",
                12,
                "callsPerRun",
                3,
                "distribution",
                "6:6",
                "teamRate",
                4,
                "teamBurst",
                2,
                "supplierRate",
                4,
                "supplierBurst",
                4,
                "responseDelayMillis",
                100,
                "deadlineMillis",
                30000));
        facts.put("elapsedMillis", elapsed);
        facts.put("results", results);
        facts.put("arrivals", arrivals);
        facts.put("usage", f.teams.usage(team.admin(), team.workspace(), null, null, 100));
        facts.put(
            "permits",
            f.db.queryForList(
                "SELECT payload FROM agent_trace WHERE run_id IN (SELECT run_id FROM team_model_execution"
                    + " WHERE workspace_id=?) AND kind='MODEL_PERMIT' ORDER BY id",
                bytes(team.workspace())));
        TeamModelFixture.JSON.writeValue(dir.resolve("summary.json").toFile(), facts);
        assertThat(results)
            .hasSize(12)
            .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SUCCEEDED"));
        assertThat(arrivals.stream().filter(r -> r.get("status").equals(200))).hasSize(36);
        assertThat(arrivals).hasSizeBetween(36,60); // 3 responses + at most 2 extra sends per run.
        assertThat(arrivals).allSatisfy(r -> assertThat(r.get("status")).isIn(200,429));
        // Check the shared permit envelope itself. Transport/scheduling after a grant can bunch
        // arrivals at the supplier; 429 retries must be counted rather than hidden by this test.
        var permits = f.db.queryForList("SELECT payload FROM agent_trace WHERE run_id IN (SELECT run_id FROM team_model_execution WHERE workspace_id=?) AND kind='MODEL_PERMIT'",bytes(team.workspace()))
            .stream().map(r -> readJson((String)r.get("payload")))
            .sorted(Comparator.comparingLong(r -> ((Number)r.get("permitAt")).longValue())).toList();
        double tokens=2;
        long last=((Number)permits.get(0).get("permitAt")).longValue();
        for(var permit:permits){
          long now=((Number)permit.get("permitAt")).longValue();
          tokens=Math.min(2,tokens+(now-last)*4/1000d); last=now;
          assertThat(tokens).as("shared team permit bucket").isGreaterThanOrEqualTo(0.999);
          tokens-=1;
        }
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE workspace_id=?",Long.class,bytes(team.workspace()))).isEqualTo(arrivals.size());
        assertThat(f.db.queryForObject("SELECT SUM(input_tokens) FROM team_model_usage WHERE workspace_id=?",Long.class,bytes(team.workspace()))).isEqualTo(360);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE workspace_id=? AND state='COMPLETE' AND error_code IS NULL",Long.class,bytes(team.workspace()))).isEqualTo(36);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE workspace_id=? AND state='COMPLETE' AND error_code IS NOT NULL",Long.class,bytes(team.workspace()))).isEqualTo(arrivals.size()-36);
        assertThat(f.teams.projectUsage(team.member(), team.first(), null, 100)).hasSize(6);
        assertThat(f.teams.projectUsage(team.member(), team.second(), null, 100)).hasSize(6);
        assertThat(elapsed)
            .isGreaterThanOrEqualTo(
                8000); // 36 requests cannot consume two independent 4/s buckets.
      }
    } finally {
      for (var child : children)
        if (child.isAlive()) {
          child.destroyForcibly();
          child.waitFor();
        }
    }
  }

  private static Map<String,Object> readJson(String value) {
    try { return TeamModelFixture.JSON.readValue(value,Map.class); }
    catch(java.io.IOException e){throw new IllegalArgumentException(e);}
  }

  @Test
  void killedJvmAfterActualHttpSendRetainsUnknownAttempt() throws Exception {
    Path dir =
        Path.of(
                "target/team-model-governance",
                LocalDate.now().toString(),
                "sent-crash-" + UUID.randomUUID())
            .toAbsolutePath();
    Files.createDirectories(dir);
    try (var http = new MockWebServer()) {
      http.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      http.start();
      try (var f =
          new TeamModelFixture(http.url("/chat/completions").toString(), Clock.systemUTC())) {
        Flyway.configure().dataSource(f.source).load().migrate();
        var t = f.team();
        f.configure(t, 1000, 1);
        UUID run = f.run(t, t.first());
        TeamModelFixture.JSON.writeValue(
            dir.resolve("input-0.json").toFile(), List.of(run.toString()));
        Files.createFile(dir.resolve("go"));
        var child =
            new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-Xmx192m",
                    "-XX:ActiveProcessorCount=2",
                    "-cp",
                    System.getProperty("java.class.path"),
                    TeamModelMultiJvmIT.class.getName(),
                    dir.toString(),
                    "0",
                    http.url("/chat/completions").toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("child.log").toFile())
                .start();
        try {
          assertThat(http.takeRequest(20, TimeUnit.SECONDS)).isNotNull();
          child.destroyForcibly();
          assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
          UUID call =
              uuid(
                  f.db.queryForObject(
                      "SELECT id FROM team_model_usage WHERE run_id=?", byte[].class, bytes(run)));
          assertThat(
                  f.db.queryForObject(
                      "SELECT state FROM team_model_usage WHERE id=?", String.class, bytes(call)))
              .isEqualTo("PENDING");
          f.audit.finish(call, null, null, 1, "PROCESS_EXIT_UNKNOWN");
          f.audit.finish(call, 10L, 5L, 1, null);
          f.audit.finishUnsent(call, 1, "LATE_UNSENT");
          assertThat(f.db.queryForObject("SELECT state FROM team_model_usage WHERE id=?",String.class,bytes(call))).isEqualTo("UNKNOWN");
          assertThat(f.db.queryForObject("SELECT input_tokens FROM team_model_usage WHERE id=?",Long.class,bytes(call))).isNull();
          TeamModelFixture.JSON.writeValue(dir.resolve("result.json").toFile(),
              Map.of("httpRequests",1,"childExit",child.exitValue(),"state","UNKNOWN","fabricatedUsage",false));
        } finally {
          if (child.isAlive()) {
            child.destroyForcibly();
            child.waitFor();
          }
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    String index = args[1];
    try (var f = new TeamModelFixture(args[2], Clock.systemUTC())) {
      List<String> inputs =
          TeamModelFixture.JSON.readValue(
              dir.resolve("input-" + index + ".json").toFile(), List.class);
      Files.createFile(dir.resolve("ready-" + index));
      while (!Files.exists(dir.resolve("go"))) Thread.sleep(10);
      var pool = Executors.newFixedThreadPool(6);
      try {
        var futures = new ArrayList<Future<Map<String, Object>>>();
        for (String id : inputs)
          futures.add(
              pool.submit(
                  () -> {
                    UUID run = UUID.fromString(id);
                    long start = System.nanoTime();
                    String status = "SUCCEEDED";
                    try {
                      for (int i = 0; i < 3; i++) f.call(run, 30000);
                    } catch (ApiException e) {
                      status = e.code;
                    }
                    return Map.of(
                        "runId",
                        id,
                        "pid",
                        ProcessHandle.current().pid(),
                        "status",
                        status,
                        "elapsedMillis",
                        (System.nanoTime() - start) / 1_000_000,
                        "calls",
                        f.audit.calls(run));
                  }));
        var results = new ArrayList<Map<String, Object>>();
        for (var future : futures) results.add(future.get(40, TimeUnit.SECONDS));
        TeamModelFixture.JSON.writeValue(
            dir.resolve("result-" + index + ".json").toFile(), results);
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
