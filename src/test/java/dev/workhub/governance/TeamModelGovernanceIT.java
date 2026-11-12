package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import dev.workhub.agent.*;
import dev.workhub.common.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class TeamModelGovernanceIT {
  TeamModelFixture f;
  MockWebServer http;
  MutableClock clock;

  static final class MutableClock extends Clock {
    final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-30T23:59:59Z"));

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now.get();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    http = new MockWebServer();
    http.start();
    clock = new MutableClock();
    f = new TeamModelFixture(http.url("/chat/completions").toString(), clock);
    Flyway.configure().dataSource(f.source).load().migrate();
  }

  @AfterEach
  void close() throws Exception {
    f.close();
    http.close();
  }

  void ok() {
    http.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"glm-4.7\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"));
  }

  void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", code);
  }

  @Test
  void configurationPermissionsRedactionEncryptionAndOptimisticVersion() {
    var a = f.team();
    var b = f.team();
    f.configure(a, 1000, 1);
    var c = f.teams.getConnection(a.admin(), a.workspace());
    assertThat(TeamModelFixture.JSON.valueToTree(c).toString())
        .doesNotContain("synthetic-team-credential", "cipher");
    assertThat(c.hasKey()).isTrue();
    assertThat(
            f.db.queryForObject(
                "SELECT key_cipher FROM team_model_connection WHERE workspace_id=?",
                String.class,
                bytes(a.workspace())))
        .doesNotContain("synthetic-team-credential");
    code(() -> f.teams.getConnection(a.member(), a.workspace()), "FORBIDDEN");
    code(() -> f.teams.getConnection(b.admin(), a.workspace()), "FORBIDDEN");
    var update =
        new TeamModels.ConnectionUpdate(
            1, "ZAI", "glm-4.7", List.of("glm-4.7"), true, 1000, 1, null);
    code(() -> f.teams.updateConnection(a.member(), a.workspace(), update), "FORBIDDEN");
    assertThat(f.teams.updateConnection(a.admin(), a.workspace(), update).version()).isEqualTo(2);
    code(() -> f.teams.updateConnection(a.admin(), a.workspace(), update), "MODEL_CONFIG_CONFLICT");
    assertThat(
            f.db
                .queryForList(
                    "SELECT detail FROM management_activity WHERE workspace_id=?",
                    bytes(a.workspace()))
                .toString())
        .doesNotContain("synthetic-team-credential");
    var cipher = new ModelKeyCipher("");
    code(() -> cipher.encrypt(a.workspace(), "fixture"), "MODEL_KEY_UNAVAILABLE");
    var encrypted = new ModelKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
    code(
        () -> encrypted.decrypt(b.workspace(), encrypted.encrypt(a.workspace(), "fixture")),
        "MODEL_KEY_UNAVAILABLE");
  }

  @Test
  void noTeamConfigurationCannotFallBackToPlatformCredential() {
    var t = f.team();
    code(() -> f.teams.available(t.first()), "MODEL_NOT_CONFIGURED");
    assertThat(http.getRequestCount()).isZero();
  }

  @Test
  void projectPolicyAndUsageAuthorizationDoNotGrantContentAccess() {
    var a = f.team();
    var b = f.team();
    f.configure(a, 1000, 1);
    f.configure(b, 1000, 1);
    code(
        () ->
            f.teams.updatePolicy(
                a.member(), a.first(), new TeamModels.PolicyUpdate(0, null, 2, 2000)),
        "FORBIDDEN");
    code(
        () ->
            f.teams.updatePolicy(
                a.admin(),
                a.first(),
                new TeamModels.PolicyUpdate(0, "qwen3.7-plus", 2, 2000)),
        "VALIDATION_ERROR");
    code(() -> f.teams.projectUsage(a.admin(), b.first(), null, 100), "FORBIDDEN");
    code(() -> f.teams.usage(a.admin(), a.workspace(), b.first(), null, 100), "FORBIDDEN");
    f.db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(a.second()),
        bytes(a.admin().userId()));
    assertThat(f.teams.usage(a.admin(), a.workspace(), null, null, 100)).isEmpty();
    code(() -> f.access.read(a.admin(), a.second()), "FORBIDDEN");
  }

  @Test
  void twoProjectsRecordSupplierUsageWithoutAnyBudgetConfiguration() {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID a = f.run(t, t.first()), b = f.run(t, t.second());
    ok(); ok(); f.call(a, 8000); f.call(b, 8000);
    var rows = f.teams.usage(t.admin(), t.workspace(), null, null, 100);
    assertThat(rows).hasSize(2).allSatisfy(row -> {
      assertThat(((Number)row.get("inputTokens")).longValue()).isEqualTo(10);
      assertThat(((Number)row.get("outputTokens")).longValue()).isEqualTo(5);
      assertThat(row).doesNotContainKeys("currency", "priceVersion", "settledNanos");
    });
    assertThat(f.teams.projectUsage(t.member(), t.first(), null, 100)).hasSize(1);
    assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_budget WHERE workspace_id=?", Long.class, bytes(t.workspace()))).isZero();
    assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_period WHERE workspace_id=?", Long.class, bytes(t.workspace()))).isZero();
  }

  @Test
  void concurrentAttemptsRespectRunCountWithoutTeamMonetaryContention() throws Exception {
    var t = f.team(); f.configure(t, 1000, 1);
    f.teams.updatePolicy(t.admin(), t.first(), new TeamModels.PolicyUpdate(0, null, 5, 30000));
    UUID run = f.run(t, t.first());
    var executor = Executors.newFixedThreadPool(10);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<String>>();
      for (int i=0;i<20;i++) futures.add(executor.submit(() -> {
        start.await();
        try { f.reserve(run); return "REGISTERED"; } catch(ApiException e) { return e.code; }
      }));
      start.countDown();
      var results = new ArrayList<String>();
      for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
      assertThat(Collections.frequency(results,"REGISTERED")).isEqualTo(5);
      assertThat(Collections.frequency(results,"AGENT_ROUND_BUDGET")).isEqualTo(15);
      assertThat(f.audit.calls(run)).isEqualTo(5);
      // Another project and execution can still register; there is no shared money hold.
      f.reserve(f.run(t,t.second()));
    } finally { executor.shutdownNow(); }
  }

  @Test
  void usageCompletionIsIdempotentAndUnknownIsNeverFabricatedZero() {
    var t=f.team(); f.configure(t,1000,1);
    UUID run=f.run(t,t.first()), a=f.reserve(run), b=f.reserve(run), c=f.reserve(run);
    for(int i=0;i<3;i++) f.audit.finish(a,10L,5L,1,null);
    f.audit.finish(b,null,null,1,"READ_TIMEOUT");
    f.audit.finish(b,10L,5L,1,null); f.audit.finishUnsent(b,1,"TOO_LATE");
    var unknown=f.db.queryForMap("SELECT * FROM team_model_usage WHERE id=?",bytes(b));
    assertThat(unknown.get("state")).isEqualTo("UNKNOWN");
    assertThat(unknown.get("input_tokens")).isNull();
    assertThat(unknown.get("usage_state")).isEqualTo("UNKNOWN");
    f.audit.finishUnsent(c,1,"NOT_SENT"); f.audit.finishUnsent(c,1,"REPLAY");
    var rows=f.teams.projectUsage(t.member(),t.first(),null,100);
    assertThat(rows).hasSize(1);
    var usage=rows.get(0);
    assertThat(((Number)usage.get("inputTokens")).longValue()).isEqualTo(10);
    assertThat(((Number)usage.get("notSentCalls")).longValue()).isEqualTo(1);
    assertThat(((Number)usage.get("unknownCalls")).longValue()).isEqualTo(1);
  }

  @Test
  void answersCompactionAndRetriesShareRequestCountAndTimeLimits() {
    var t=f.team(); f.configure(t,1000,1);
    f.teams.updatePolicy(t.admin(),t.first(),new TeamModels.PolicyUpdate(0,null,2,1000));
    UUID run=f.run(t,t.first());
    f.reserve(run); f.audit.reserve(run,run,1000,4096,"COMPACTION");
    code(()->f.reserve(run),"AGENT_ROUND_BUDGET");
    assertThat(f.teams.maxMillis(run)).isEqualTo(1000);
    var contract=f.db.queryForObject("SELECT model_contract FROM agent_run WHERE id=?",String.class,bytes(run));
    assertThat(contract).contains("USAGE_ONLY").doesNotContain("Price", "price", "currency", "maxCost");
  }

  @Test
  void lateUsageRemainsInOriginalUtcMonthWithoutMonthlyAdmission() {
    var t=f.team(); f.configure(t,1000,1);
    UUID run=f.run(t,t.first()), call=f.reserve(run);
    clock.now.set(Instant.parse("2026-12-01T00:00:00Z"));
    UUID next=f.reserve(run);
    f.audit.finish(call,10L,5L,1,null);
    var november=f.teams.projectUsage(t.admin(),t.first(),LocalDate.of(2026,11,1),100).get(0);
    assertThat(((Number)november.get("inputTokens")).longValue()).isEqualTo(10);
    var december=f.teams.projectUsage(t.admin(),t.first(),null,100).get(0);
    assertThat(december.get("inputTokens")).isNull();
    assertThat(((Number)december.get("pendingCalls")).longValue()).isEqualTo(1);
    f.audit.finishUnsent(next,1,"UNSENT");
  }

  @Test
  void rotationStopsWaitingCallButSettlesSentCallsWithoutResettingQuota() throws Exception {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first()), sent = f.reserve(run);
    var old = f.quota(t);
    var permit = f.gate.acquire(old);
    assertThat(permit.permitted()).isTrue();
    f.redis()
        .opsForHash()
        .put(f.key(t.workspace()), "cooldown", "" + (System.currentTimeMillis() + 30000));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future =
          executor.submit(
              () -> {
                try {
                  f.call(run, 8000);
                  return "SENT";
                } catch (ApiException e) {
                  return e.code;
                }
              });
      await(
          () ->
              f.db.queryForObject(
                      "SELECT COUNT(*) FROM agent_trace WHERE run_id=? AND kind='MODEL_WAIT'",
                      Integer.class,
                      bytes(run))
                  > 0);
      assertThat(f.source.getHikariPoolMXBean().getActiveConnections()).isZero();
      f.teams.updateConnection(
          t.admin(),
          t.workspace(),
          new TeamModels.ConnectionUpdate(
              1, "ZAI", "glm-5.3", List.of("glm-4.7", "glm-5.3"), true, 4, 2, "rotated-synthetic"));
      assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("MODEL_CONNECTION_CHANGED");
      assertThat(http.getRequestCount()).isZero();
      f.audit.finish(sent, 10L, 5L, 1, null);
      assertThat(f.db.queryForObject("SELECT input_tokens FROM team_model_usage WHERE id=?", Long.class, bytes(sent))).isEqualTo(10);
      var decision = f.gate.acquire(f.quota(t));
      assertThat(decision.reason()).isEqualTo("COOLDOWN");
      assertThat(f.redis().opsForHash().get(f.key(t.workspace()), "tokens")).isEqualTo("0");
      f.gate.outcome(old, permit, 429, 40000);
      assertThat(f.gate.acquire(f.quota(t)).reason()).isEqualTo("COOLDOWN");
      code(() -> f.gate.acquire(old), "MODEL_ADMISSION_CONFIG");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void disabledConnectionAndRevokedMemberStopNextSend() {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first(), t.member(), "MANUAL_QUESTION");
    f.db.update(
        "UPDATE project_member SET active=false WHERE project_id=? AND user_id=?",
        bytes(t.first()),
        bytes(t.member().userId()));
    code(() -> f.call(run, 5000), "FORBIDDEN");
    UUID other = f.run(t, t.second());
    f.teams.updateConnection(
        t.admin(),
        t.workspace(),
        new TeamModels.ConnectionUpdate(
            1, "ZAI", "glm-4.7", List.of("glm-4.7"), false, 1000, 1, null));
    code(() -> f.call(other, 5000), "MODEL_CONNECTION_CHANGED");
    assertThat(http.getRequestCount()).isZero();
  }

  @Test
  void independentTeamIgnoresOtherTeamsCooldown() {
    var a = f.team();
    var b = f.team();
    f.configure(a, 1000, 1);
    f.configure(b, 1000, 1);
    UUID aRun = f.run(a, a.first()), aOther = f.run(a, a.second()), bRun = f.run(b, b.first());
    f.reserve(aRun);
    f.reserve(aOther);
    f.redis()
        .opsForHash()
        .put(f.key(a.workspace()), "cooldown", "" + (System.currentTimeMillis() + 30000));
    assertThat(f.gate.acquire(f.quota(a)).reason()).isEqualTo("COOLDOWN");
    ok();
    assertThat(f.call(bRun, 8000).inputTokens()).isEqualTo(10);
  }

  @Test
  void redisStateLossFailsClosedAndDoesNotAffectOrdinaryProjectReads() {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first());
    f.redis().delete(f.key(t.workspace()));
    code(() -> f.call(run, 5000), "MODEL_ADMISSION_MISSING");
    assertThat(http.getRequestCount()).isZero();
    assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state='NOT_SENT'", Long.class, bytes(run))).isEqualTo(1);
    assertThat(f.access.read(t.member(), t.first()).id()).isEqualTo(t.first());
  }

  @Test
  void redisOutageAndExplicitRecoveryRemainConservative() throws Exception {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first());
    try (var unavailable =
        new ModelAdmission(true, "isolated-outage", 1000, 1, "127.0.0.1", 1, "")) {
      org.springframework.test.util.ReflectionTestUtils.setField(
          f.requests, "admission", unavailable);
      code(() -> f.call(run, 5000), "MODEL_ADMISSION_UNAVAILABLE");
      assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state='NOT_SENT'", Long.class, bytes(run))).isEqualTo(1);
      assertThat(f.access.read(t.member(), t.first()).id()).isEqualTo(t.first());
    }
    org.springframework.test.util.ReflectionTestUtils.setField(f.requests, "admission", f.gate);
    f.redis().delete(f.key(t.workspace()));
    var script = new org.springframework.data.redis.core.script.DefaultRedisScript<List>();
    script.setLocation(new org.springframework.core.io.ClassPathResource("model/admission.lua"));
    script.setResultType(List.class);
    var result =
        f.redis()
            .execute(
                script,
                List.of(f.key(t.workspace()), f.key(t.workspace()) + ":init"),
                "initialize",
                "team-v1|1|1000.0|1",
                "1000",
                "1",
                "1000",
                UUID.randomUUID().toString());
    assertThat(result.get(0)).isEqualTo("INITIALIZED");
    assertThat(f.gate.acquire(f.quota(t)).reason()).isEqualTo("COOLDOWN");
    assertThat(f.redis().opsForHash().get(f.key(t.workspace()), "tokens")).isEqualTo("0");
    Thread.sleep(1100);
    ok();
    assertThat(f.call(run, 8000).inputTokens()).isEqualTo(10);
  }

  @Test
  void deploymentModeAndPolicyEditsCannotChangeOldExecutionIdentity() {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first());
    f.platform.legacyMode = true;
    code(() -> f.call(run, 5000), "MODEL_MODE_CHANGED");
    f.platform.legacyMode = false;
    f.teams.updatePolicy(
        t.admin(), t.first(), new TeamModels.PolicyUpdate(0, null, 5, 5000));
    code(() -> f.call(run, 5000), "MODEL_POLICY_CHANGED");
    assertThat(http.getRequestCount()).isZero();
  }

  @Test
  void maintainerCanRepairModelPolicyAfterTeamAllowlistChanges() {
    var t = f.team();
    f.configure(t, 1000, 1);
    f.teams.updatePolicy(
        t.admin(), t.first(), new TeamModels.PolicyUpdate(0, "glm-5.3", 3, 30000));
    f.teams.updateConnection(
        t.admin(),
        t.workspace(),
        new TeamModels.ConnectionUpdate(
            1, "ZAI", "glm-4.7", List.of("glm-4.7"), true, 1000, 1, null));
    assertThat(f.teams.getPolicy(t.admin(), t.first()).modelAllowed()).isFalse();
    code(() -> f.teams.available(t.first()), "MODEL_POLICY_INVALID");
    assertThat(
            f.teams
                .updatePolicy(
                    t.admin(), t.first(), new TeamModels.PolicyUpdate(1, null, 3, 30000))
                .modelAllowed())
        .isTrue();
    f.teams.available(t.first());
  }

  @Test
  void readTimeoutKeepsUnknownAndEveryRetryHasItsOwnPhysicalRow() {
    var t = f.team();
    f.configure(t, 1000, 1);
    UUID run = f.run(t, t.first());
    http.enqueue(new MockResponse().setResponseCode(503));
    ok();
    f.call(run, 8000);
    assertThat(f.audit.calls(run)).isEqualTo(2);
    assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state='COMPLETE' AND error_code IS NOT NULL", Long.class, bytes(run))).isEqualTo(1);
    http.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}"));
    code(() -> f.call(run, 400), "GLM_TIMEOUT");
    assertThat(f.audit.calls(run)).isEqualTo(3);
    assertThat(f.db.queryForObject("SELECT COUNT(*) FROM team_model_usage WHERE run_id=? AND state='UNKNOWN'", Long.class, bytes(run))).isEqualTo(1);
  }

  @Test
  void partialAndAboveLocalBoundUsageAreRecordedWithoutPriceMath() {
    var t=f.team(); f.configure(t,1000,1);
    UUID run=f.run(t,t.first()), a=f.reserve(run), b=f.reserve(run);
    f.audit.finish(a,Long.MAX_VALUE,null,1,null);
    f.audit.finish(b,0L,0L,1,null);
    var partial=f.db.queryForMap("SELECT * FROM team_model_usage WHERE id=?",bytes(a));
    assertThat(partial.get("state")).isEqualTo("COMPLETE");
    assertThat(partial.get("usage_state")).isEqualTo("PARTIAL");
    assertThat(partial.get("input_tokens")).isEqualTo(Long.MAX_VALUE);
    assertThat(partial.get("output_tokens")).isNull();
    assertThat(f.db.queryForObject("SELECT usage_state FROM team_model_usage WHERE id=?",String.class,bytes(b))).isEqualTo("KNOWN");
  }

  @Test
  void validResponseWithoutUsageStillCompletesAndReportsUnknownUsage() {
    var t=f.team(); f.configure(t,1000,1); UUID run=f.run(t,t.first());
    http.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(
        "{\"id\":\"fixture\",\"model\":\"glm-4.7\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}"));
    f.call(run,8000);
    var row=f.db.queryForMap("SELECT * FROM team_model_usage WHERE run_id=?",bytes(run));
    assertThat(row.get("state")).isEqualTo("COMPLETE");
    assertThat(row.get("usage_state")).isEqualTo("UNKNOWN");
    assertThat(row.get("input_tokens")).isNull();
  }

  @Test
  void changingProviderDoesNotRequireCurrencyOrPriceConfiguration() {
    var t=f.team(); f.configure(t,1000,1);
    var c=f.teams.updateConnection(t.admin(),t.workspace(),new TeamModels.ConnectionUpdate(
        1,"DASHSCOPE","qwen3.7-plus",List.of("qwen3.7-plus"),true,1000,1,"synthetic-new-provider"));
    assertThat(c.provider()).isEqualTo("DASHSCOPE");
    f.teams.available(t.first()); UUID run=f.run(t,t.first());
    assertThat(f.teams.runtime(run).legacyMode).isFalse();
    assertThat(AgentProvider.QWEN.protocol()).isEqualTo("dashscope-compatible-v1");
  }

  @Test
  void existingZeroBudgetIsIgnoredAndNeverRewrittenByNewCalls() {
    var t=f.team(); f.configure(t,1000,1);
    f.db.update("INSERT INTO team_model_budget(workspace_id,currency,limit_nanos,version,updated_by) VALUES (?,'USD',0,1,?)",bytes(t.workspace()),bytes(t.admin().userId()));
    f.db.update("INSERT INTO team_model_period(workspace_id,period_start,currency,limit_nanos) VALUES (?,'2026-11-01','USD',0)",bytes(t.workspace()));
    UUID run=f.run(t,t.first()); ok(); f.call(run,8000);
    assertThat(f.db.queryForObject("SELECT limit_nanos FROM team_model_budget WHERE workspace_id=?",Long.class,bytes(t.workspace()))).isZero();
    assertThat(f.db.queryForObject("SELECT settled_nanos+pending_nanos FROM team_model_period WHERE workspace_id=?",Long.class,bytes(t.workspace()))).isZero();
  }

  static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).isLessThan(deadline);
      Thread.sleep(20);
    }
  }
}
