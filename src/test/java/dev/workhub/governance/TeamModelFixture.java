package dev.workhub.governance;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.onboarding.Workspaces;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL/Redis, synthetic credentials, loopback HTTP only. Shared by tests and child JVMs. */
public final class TeamModelFixture implements AutoCloseable {
  public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  public final HikariDataSource source;
  public final JdbcTemplate db;
  public final TransactionTemplate tx;
  public final TeamModels teams;
  public final AgentAudit audit;
  public final ModelAdmission gate;
  public final GovernedModelRequests requests;
  public final AgentSettings platform;
  public final ProjectAccess access;

  public TeamModelFixture(String endpoint, Clock clock) {
    var cfg = new HikariConfig();
    cfg.setJdbcUrl(
        System.getenv()
            .getOrDefault(
                "TEAM_MODEL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:28306/workhub_team_model_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
    cfg.setUsername(System.getenv().getOrDefault("TEAM_MODEL_DB_USER", "team_model_fixture"));
    cfg.setPassword(
        System.getenv().getOrDefault("TEAM_MODEL_DB_PASSWORD", "team-model-local-only"));
    cfg.setMaximumPoolSize(8);
    cfg.setMinimumIdle(0);
    cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    source = new HikariDataSource(cfg);
    db = new JdbcTemplate(source);
    if (!Set.of("workhub_team_model_test", "workhub_model_ui_test")
        .contains(db.queryForObject("SELECT DATABASE()", String.class)))
      throw new IllegalStateException("Dedicated team test database required");
    var manager = new DataSourceTransactionManager(source);
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(2);
    var identities = new IdentityRepository(db);
    access = new ProjectAccess(identities, new ProjectRepository(db));
    var json = new Json(JSON);
    var workspaces = new Workspaces(db, identities, null, json, null);
    platform =
        new AgentSettings(
            true,
            true,
            "http://127.0.0.1:1/chat/completions",
            "platform-must-never-be-used",
            "glm-4.7",
            "legacy-unused",
            BigDecimal.ONE,
            0,
            0);
    platform.legacyMode = false;
    var target =
        new TeamModels(
            db,
            json,
            workspaces,
            access,
            new ModelKeyCipher(Base64.getEncoder().encodeToString(new byte[32])),
            platform,
            endpoint,
            clock);
    teams = proxy(target, manager);
    var usage = new TeamModelUsage(db, teams, platform);
    var accounting = new AgentAudit(db, json, platform, identities);
    ReflectionTestUtils.setField(accounting, "teams", teams);
    ReflectionTestUtils.setField(accounting, "teamUsage", usage);
    audit = proxy(accounting, manager);
    gate =
        new ModelAdmission(
            true,
            "team-fixture-bootstrap",
            1000,
            1,
            "127.0.0.1",
            Integer.parseInt(System.getenv().getOrDefault("TEAM_MODEL_REDIS_PORT", "28379")),
            "");
    requests = new GovernedModelRequests(gate, audit, platform);
    ReflectionTestUtils.setField(requests, "teams", teams);
  }

  private static <T> T proxy(T target, DataSourceTransactionManager manager) {
    var p = new ProxyFactory(target);
    p.setProxyTargetClass(true);
    p.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) p.getProxy();
  }

  public record Team(
      UUID workspace, ActorContext admin, ActorContext member, UUID first, UUID second) {}

  public Team team() {
    var admin = user();
    var member = user();
    UUID w = UUID.randomUUID(), p = UUID.randomUUID(), q = UUID.randomUUID();
    tx.executeWithoutResult(
        t -> {
          db.update("INSERT INTO workspace(id,name) VALUES (?,'Team model fixture')", bytes(w));
          db.update(
              "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES"
                  + " (?,?,'ADMIN'),(?,?,'MEMBER')",
              bytes(w),
              bytes(admin.userId()),
              bytes(w),
              bytes(member.userId()));
          for (UUID project : List.of(p, q)) {
            db.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Team project fixture')",
                bytes(project),
                bytes(w));
            db.update(
                "INSERT INTO project_member(project_id,user_id,role) VALUES"
                    + " (?,?,'MAINTAINER'),(?,?,'MEMBER')",
                bytes(project),
                bytes(admin.userId()),
                bytes(project),
                bytes(member.userId()));
          }
        });
    return new Team(w, admin, member, p, q);
  }

  public ActorContext user() {
    UUID u = UUID.randomUUID();
    db.update(
        "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'Team"
            + " fixture','unused')",
        bytes(u),
        "team-" + u);
    return new ActorContext(u);
  }

  public void configure(Team t, double rate, int burst) {
    teams.updateConnection(
        t.admin(),
        t.workspace(),
        new TeamModels.ConnectionUpdate(
            0,
            "ZAI",
            "glm-4.7",
            List.of("glm-4.7", "glm-5.3"),
            true,
            rate,
            burst,
            "synthetic-team-credential"));
    initialize(t.workspace(), 1, rate, burst);
  }

  public String key(UUID w) {
    return "workhub:model:{" + TeamModels.scope(w) + "}";
  }

  public StringRedisTemplate redis() {
    return (StringRedisTemplate) ReflectionTestUtils.getField(gate, "redis");
  }

  public void initialize(UUID w, long version, double rate, int burst) {
    var script = new DefaultRedisScript<List>();
    script.setLocation(new ClassPathResource("model/admission.lua"));
    script.setResultType(List.class);
    String k = key(w);
    long recovery = (long) Math.ceil(1000 * burst / rate);
    var result =
        redis()
            .execute(
                script,
                List.of(k, k + ":init"),
                "initialize",
                "team-v1|" + version + "|" + rate + "|" + burst,
                "" + rate,
                "" + burst,
                "" + recovery,
                UUID.randomUUID().toString());
    if (!result.get(0).equals("INITIALIZED")) throw new AssertionError(result);
    try {
      Thread.sleep(recovery + 2);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public UUID run(Team t, UUID project) {
    return run(t, project, t.admin(), "MANUAL_QUESTION");
  }

  public UUID run(Team t, UUID project, ActorContext actor, String source) {
    UUID id = UUID.randomUUID();
    tx.executeWithoutResult(
        s -> {
          db.update(
              "INSERT INTO"
                  + " agent_run(id,project_id,actor_id,question,model_contract,business_revision,status,attempt_token)"
                  + " VALUES (?,?,?,'Team fixture','{}',0,'RUNNING',?)",
              bytes(id),
              bytes(project),
              bytes(actor.userId()),
              bytes(id));
          teams.bind(id, project, source);
        });
    return id;
  }

  public UUID reserve(UUID run) {
    return audit.reserve(run, run, 1000, 4096);
  }

  public AgentModel.Reply call(UUID run, long millis) {
    return requests.call(
        run,
        run,
        null,
        List.of(new org.springframework.ai.chat.messages.UserMessage("fixture/" + run)),
        List.of(),
        1000,
        System.nanoTime() + millis * 1_000_000L,
        () -> {},
        () -> {});
  }

  public ModelAdmission.Quota quota(Team t) {
    var c = teams.getConnection(t.admin(), t.workspace());
    return new ModelAdmission.Quota(c.quotaScope(), c.version(), c.requestsPerSecond(), c.burst());
  }

  public <T> T transaction(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  public void close() {
    gate.close();
    source.close();
  }
}
