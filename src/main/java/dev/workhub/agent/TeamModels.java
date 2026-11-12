package dev.workhub.agent;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.onboarding.Workspaces;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Workspace configuration and immutable execution/credential identity. Never returns credentials.
 */
@Service
public class TeamModels {
  private final JdbcTemplate db;
  private final Json json;
  private final Workspaces workspaces;
  private final ProjectAccess access;
  private final ModelKeyCipher cipher;
  private final AgentSettings platform;
  private final String fixtureEndpoint;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public TeamModels(
      JdbcTemplate db,
      Json json,
      Workspaces workspaces,
      ProjectAccess access,
      ModelKeyCipher cipher,
      AgentSettings platform,
      @Value("${workhub.team-model.fixture-endpoint:}") String fixtureEndpoint) {
    this(db, json, workspaces, access, cipher, platform, fixtureEndpoint, Clock.systemUTC());
  }

  // Clock is injectable in component tests; production periods always use UTC.
  public TeamModels(
      JdbcTemplate db,
      Json json,
      Workspaces workspaces,
      ProjectAccess access,
      ModelKeyCipher cipher,
      AgentSettings platform,
      String fixtureEndpoint,
      Clock clock) {
    this.db = db;
    this.json = json;
    this.workspaces = workspaces;
    this.access = access;
    this.cipher = cipher;
    this.platform = platform;
    this.clock = clock;
    this.fixtureEndpoint = fixtureEndpoint;
    if (!fixtureEndpoint.isBlank()) {
      URI u = URI.create(fixtureEndpoint);
      if (!"http".equals(u.getScheme())
          || !Set.of("localhost", "127.0.0.1", "::1", "[::1]")
              .contains(Objects.toString(u.getHost(), ""))
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null)
        throw new IllegalArgumentException(
            "Fixture endpoint must be server-configured loopback HTTP");
    }
  }

  public boolean legacy() {
    return platform.legacyMode;
  }

  public LocalDate period() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
  }

  public record ConnectionUpdate(
      long expectedVersion,
      String provider,
      String model,
      List<String> allowedModels,
      boolean enabled,
      double requestsPerSecond,
      int burst,
      String apiKey) {
    @Override
    public String toString() {
      return "ConnectionUpdate[credentials redacted]";
    }
  }

  public record ConnectionView(
      long version,
      String provider,
      String model,
      List<String> allowedModels,
      boolean enabled,
      double requestsPerSecond,
      int burst,
      boolean hasKey,
      String keyMarker,
      String quotaScope) {}

  public record PolicyUpdate(long expectedVersion, String model, int maxCalls, int maxMillis) {}

  public record PolicyView(
      long version,
      String model,
      List<String> allowedModels,
      int maxCalls,
      int maxMillis,
      boolean inherited,
      boolean modelAllowed) {}

  static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private UUID workspace(UUID project) {
    return db
        .query(
            "SELECT workspace_id FROM project WHERE id=?",
            (r, n) -> read(r, "workspace_id"),
            bytes(project))
        .stream()
        .findFirst()
        .orElseThrow(ApiException::forbidden);
  }

  private Map<String, Object> connection(UUID workspace) {
    return db
        .queryForList("SELECT * FROM team_model_connection WHERE workspace_id=?", bytes(workspace))
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(503, "MODEL_NOT_CONFIGURED", "团队尚未配置生成模型连接"));
  }

  private List<String> allowed(Map<String, Object> row) {
    return Arrays.asList(json.read((String) row.get("allowed_models"), String[].class));
  }

  private ConnectionView view(UUID w, Map<String, Object> r) {
    return new ConnectionView(
        n(r, "version"),
        (String) r.get("provider"),
        (String) r.get("default_model"),
        allowed(r),
        Boolean.TRUE.equals(r.get("enabled")),
        ((Number) r.get("request_rate")).doubleValue(),
        (int) n(r, "request_burst"),
        true,
        "********",
        scope(w));
  }

  public static String scope(UUID workspace) {
    return "team-" + workspace;
  }

  public static String provider(AgentProvider p) {
    return p == AgentProvider.GLM || p == AgentProvider.GLM53
        ? "ZAI"
        : p == AgentProvider.QWEN ? "DASHSCOPE" : "ARK";
  }

  private AgentProvider catalog(String model) {
    try {
      return AgentProvider.forModel(model);
    } catch (RuntimeException e) {
      throw ApiException.invalid("模型不在服务端目录中");
    }
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> catalog(ActorContext a, UUID w) {
    workspaces.admin(a, w);
    return Arrays.stream(AgentProvider.values())
        .map(p -> Map.<String, Object>of("provider", provider(p), "model", p.model))
        .toList();
  }

  @Transactional(readOnly = true)
  public ConnectionView getConnection(ActorContext a, UUID w) {
    workspaces.admin(a, w);
    return view(w, connection(w));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ConnectionView updateConnection(ActorContext a, UUID w, ConnectionUpdate u) {
    workspaces.lock(w);
    workspaces.admin(a, w);
    var old =
        db.queryForList(
            "SELECT * FROM team_model_connection WHERE workspace_id=? FOR UPDATE", bytes(w));
    long version = old.isEmpty() ? 0 : n(old.get(0), "version");
    expected(version, u.expectedVersion());
    AgentProvider p = catalog(u.model());
    if (!provider(p).equals(u.provider())
        || u.allowedModels() == null
        || u.allowedModels().isEmpty()
        || u.allowedModels().size() > 4
        || !u.allowedModels().contains(u.model())
        || new HashSet<>(u.allowedModels()).size() != u.allowedModels().size())
      throw ApiException.invalid("需要同一供应商的允许模型集合");
    for (String m : u.allowedModels())
      if (!provider(catalog(m)).equals(u.provider())) throw ApiException.invalid("允许模型须属于同一供应商");
    if (!Double.isFinite(u.requestsPerSecond())
        || u.requestsPerSecond() < 0.001
        || u.requestsPerSecond() > 10000
        || u.burst() < 1
        || u.burst() > 10000) throw ApiException.invalid("调用速率或突发容量超出范围");
    if (!old.isEmpty() && !old.get(0).get("provider").equals(u.provider()) && u.apiKey() == null)
      throw ApiException.invalid("更换供应商需要替换密钥");
    String encrypted =
        u.apiKey() == null
            ? (old.isEmpty() ? null : (String) old.get(0).get("key_cipher"))
            : cipher.encrypt(w, u.apiKey());
    if (encrypted == null) throw ApiException.invalid("首次配置需要密钥；apiKey=null 表示保留原密钥");
    db.update(
        "INSERT INTO"
            + " team_model_connection(workspace_id,version,provider,default_model,allowed_models,key_cipher,enabled,request_rate,request_burst,updated_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " version=VALUES(version),provider=VALUES(provider),default_model=VALUES(default_model),allowed_models=VALUES(allowed_models),key_cipher=VALUES(key_cipher),enabled=VALUES(enabled),request_rate=VALUES(request_rate),request_burst=VALUES(request_burst),updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(6)",
        bytes(w),
        version + 1,
        u.provider(),
        u.model(),
        json.write(u.allowedModels()),
        encrypted,
        u.enabled(),
        u.requestsPerSecond(),
        u.burst(),
        bytes(a.userId()));
    workspaces.audit(
        a,
        w,
        null,
        "MODEL_CONNECTION_UPDATED",
        Map.of(
            "version",
            version + 1,
            "keyReplaced",
            u.apiKey() != null,
            "enabled",
            u.enabled(),
            "rate",
            u.requestsPerSecond(),
            "burst",
            u.burst(),
            "provider",
            u.provider(),
            "model",
            u.model(),
            "allowedModels",
            u.allowedModels()));
    return view(w, connection(w));
  }

  private void expected(long current, long requested) {
    if (current != requested) throw ApiException.conflict("MODEL_CONFIG_CONFLICT", current);
  }

  private PolicyView policy(UUID project) {
    UUID w = workspace(project);
    var c = connection(w);
    var rows =
        db.queryForList("SELECT * FROM project_model_policy WHERE project_id=?", bytes(project));
    if (rows.isEmpty())
      return new PolicyView(0, (String) c.get("default_model"), allowed(c), 20, 300000, true, true);
    var r = rows.get(0);
    String m = r.get("model") == null ? (String) c.get("default_model") : (String) r.get("model");
    return new PolicyView(
        n(r, "version"),
        m,
        allowed(c),
        (int) n(r, "max_calls"),
        (int) n(r, "max_millis"),
        r.get("model") == null,
        allowed(c).contains(m));
  }

  @Transactional(readOnly = true)
  public PolicyView getPolicy(ActorContext a, UUID project) {
    access.read(a, project);
    return policy(project);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PolicyView updatePolicy(ActorContext a, UUID project, PolicyUpdate u) {
    var p = access.lock(a, project);
    access.maintainer(a, project);
    var old = policy(project);
    expected(old.version(), u.expectedVersion());
    if (u.model() != null && !old.allowedModels().contains(u.model()))
      throw ApiException.invalid("只能选择团队允许模型");
    if (u.maxCalls() < 1 || u.maxCalls() > 20 || u.maxMillis() < 1000 || u.maxMillis() > 300000)
      throw ApiException.invalid("单次分析限制超出团队或服务端边界");
    db.update(
        "INSERT INTO project_model_policy(project_id,model,max_calls,max_millis,version,updated_by)"
            + " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " model=VALUES(model),max_calls=VALUES(max_calls),max_cost_nanos=NULL,max_millis=VALUES(max_millis),version=VALUES(version),updated_by=VALUES(updated_by)",
        bytes(project),
        u.model(),
        u.maxCalls(),
        u.maxMillis(),
        old.version() + 1,
        bytes(a.userId()));
    workspaces.audit(
        a,
        p.workspaceId(),
        project,
        "MODEL_POLICY_UPDATED",
        Map.of(
            "version",
            old.version() + 1,
            "model",
            Objects.toString(u.model(), "INHERIT"),
            "maxCalls",
            u.maxCalls(),
            "maxMillis",
            u.maxMillis()));
    return policy(project);
  }

  public void available(UUID project) {
    if (legacy()) {
      platform.available();
      return;
    }
    if (!platform.enabled) throw new ApiException(503, "AGENT_DISABLED", "生成模型功能尚未启用");
    var c = connection(workspace(project));
    if (!Boolean.TRUE.equals(c.get("enabled")))
      throw new ApiException(503, "MODEL_CONNECTION_DISABLED", "团队模型连接已禁用");
    var p = policy(project);
    if (!p.modelAllowed())
      throw new ApiException(503, "MODEL_POLICY_INVALID", "项目模型已不在团队允许集合中，请维护者更新策略");
  }

  public boolean bound(UUID run) {
    return db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM team_model_execution WHERE run_id=?)",
        Boolean.class,
        bytes(run));
  }

  public void bind(UUID run, UUID project, String source) {
    if (legacy()) return;
    available(project);
    UUID w = workspace(project);
    var c = connection(w);
    var policy = policy(project);
    var model = catalog(policy.model());
    db.update(
        "INSERT INTO"
            + " team_model_execution(run_id,workspace_id,project_id,connection_version,policy_version,provider,model,max_calls,max_millis,trigger_source)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        bytes(run),
        bytes(w),
        bytes(project),
        n(c, "version"),
        policy.version(),
        provider(model),
        model.model,
        policy.maxCalls(),
        policy.maxMillis(),
        source);
    var contract =
        new TreeMap<String, Object>(
            json.read(
                db.queryForObject(
                    "SELECT model_contract FROM agent_run WHERE id=?", String.class, bytes(run)),
                Map.class));
    contract.put("model", model.model);
    contract.put("endpoint", fixtureEndpoint.isBlank() ? model.endpoint : fixtureEndpoint);
    contract.remove("currency");
    contract.put("accountingMode", "USAGE_ONLY");
    contract.put("fixture", !fixtureEndpoint.isBlank());
    contract.put("protocol", model.protocol());
    contract.put("toolPhaseFormat", model == AgentProvider.GLM53 ? "native-tools" : "json");
    contract.put("thinking", model == AgentProvider.GLM53 ? "enabled" : "disabled");
    if (model == AgentProvider.GLM53) {
      contract.put("reasoningEffort", "low");
      contract.put("clearThinking", true);
    } else {
      contract.remove("reasoningEffort");
      contract.remove("clearThinking");
    }
    contract.put("maxModelCalls", policy.maxCalls());
    contract.put("maxMillis", policy.maxMillis());
    contract.remove("maxCostNanos");
    contract.put(
        "teamGovernance",
        Map.of(
            "workspaceId",
            w,
            "projectId",
            project,
            "connectionVersion",
            n(c, "version"),
            "policyVersion",
            policy.version()));
    contract.put(
        "modelAdmission",
        Map.of(
            "enabled",
            true,
            "quotaScope",
            scope(w),
            "requestsPerSecond",
            c.get("request_rate"),
            "burst",
            c.get("request_burst")));
    db.update("UPDATE agent_run SET model_contract=? WHERE id=?", json.write(contract), bytes(run));
  }

  /**
   * Called before every acquisition, in the short authorization transaction. No decrypted key
   * returned.
   */
  public void verifyMode(UUID run) {
    if (db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM team_model_run WHERE run_id=?)", Boolean.class, bytes(run)))
      throw new ApiException(503, "MODEL_LEGACY_RUN", "旧费用模式执行仅保留历史，请重新发起");
    if (legacy() && bound(run))
      throw new ApiException(503, "MODEL_MODE_CHANGED", "团队执行不能切换为部署凭据，请重新发起");
    if (!legacy() && !bound(run))
      throw new ApiException(503, "MODEL_LEGACY_RUN", "旧执行不能自动使用团队凭据，请重新发起");
  }

  public Map<String, Object> verify(UUID run) {
    verifyMode(run);
    var r =
        db.queryForList("SELECT * FROM team_model_execution WHERE run_id=?", bytes(run)).stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(503, "MODEL_LEGACY_RUN", "旧执行不能自动使用团队凭据，请重新发起"));
    UUID w = uuid((byte[]) r.get("workspace_id")), p = uuid((byte[]) r.get("project_id"));
    if (!w.equals(workspace(p))) throw ApiException.forbidden();
    var c = connection(w);
    if (!Boolean.TRUE.equals(c.get("enabled")) || n(c, "version") != n(r, "connection_version"))
      throw new ApiException(503, "MODEL_CONNECTION_CHANGED", "团队模型连接已变更或禁用，请重新发起");
    if (!platform.enabled) throw new ApiException(503, "AGENT_DISABLED", "生成模型功能尚未启用");
    var row =
        db.queryForMap(
            "SELECT actor_id,automatic_task_id,triage_task_id FROM agent_run WHERE id=?",
            bytes(run));
    UUID user = uuid((byte[]) row.get("actor_id"));
    if (row.get("automatic_task_id") != null) {
      if (!db.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM project_triage_settings WHERE project_id=? AND enabled)",
          Boolean.class,
          bytes(p))) throw new ApiException(503, "AUTOMATIC_TRIAGE_DISABLED", "自动分析已关闭");
      access.read(
          ActorContext.projectAnalysis(user, p, uuid((byte[]) row.get("triage_task_id"))), p);
    } else {
      var actor = new ActorContext(user);
      access.read(actor, p);
      if (row.get("triage_task_id") != null) access.maintainer(actor, p);
    }
    // Policy edits invalidate old runs as well; no enlarged limits on recovery.
    var policies =
        db.queryForList("SELECT version FROM project_model_policy WHERE project_id=?", bytes(p));
    if ((policies.isEmpty() ? 0 : n(policies.get(0), "version")) != n(r, "policy_version"))
      throw new ApiException(503, "MODEL_POLICY_CHANGED", "项目模型策略已更新，请重新发起");
    return r;
  }

  public AgentSettings runtime(UUID run) {
    var r = verify(run);
    UUID w = uuid((byte[]) r.get("workspace_id"));
    var conn = connection(w);
    var p = catalog((String) r.get("model"));
    var s =
        AgentSettings.teamRuntime(
            fixtureEndpoint.isBlank() ? p.endpoint : fixtureEndpoint,
            cipher.decrypt(w, (String) conn.get("key_cipher")),
            p.model);
    s.maxRequestInputBound = platform.maxRequestInputBound;
    s.sharedAdmission = true;
    s.quotaScope = scope(w);
    s.requestRate = ((Number) conn.get("request_rate")).doubleValue();
    s.requestBurst = (int) n(conn, "request_burst");
    return s;
  }

  public int maxMillis(UUID run) {
    return bound(run) ? (int) n(verify(run), "max_millis") : AgentSettings.MAX_MILLIS;
  }

  public String callTable(UUID run) {
    if (bound(run)) return "team_model_usage";
    return db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM team_model_run WHERE run_id=?)", Boolean.class, bytes(run))
        ? "team_model_call"
        : platform.callTable();
  }

  public String model(UUID run) {
    return bound(run) ? (String) verify(run).get("model") : platform.model;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> usage(
      ActorContext a, UUID w, UUID project, LocalDate month, int limit) {
    workspaces.admin(a, w);
    if (project != null && !w.equals(workspace(project))) throw ApiException.forbidden();
    return usageRows(w, project, month, limit);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> projectUsage(
      ActorContext a, UUID project, LocalDate month, int limit) {
    access.read(a, project);
    return usageRows(workspace(project), project, month, limit);
  }

  private List<Map<String, Object>> usageRows(UUID w, UUID p, LocalDate month, int limit) {
    if (limit < 1 || limit > 100) throw ApiException.invalid("limit 范围为 1 到 100");
    if (month == null) month = period();
    if (month.getDayOfMonth() != 1) throw ApiException.invalid("period 必须是 UTC 月份首日");
    var args = new ArrayList<Object>(List.of(bytes(w), month));
    String filter = p == null ? "" : " AND c.project_id=?";
    if (p != null) args.add(bytes(p));
    args.add(bytes(w));
    args.add(month);
    args.add(month);
    if (p != null) args.add(bytes(p));
    args.add(limit);
    return db.queryForList(
        "SELECT * FROM (SELECT 'TEXT_GENERATION' capability,BIN_TO_UUID(c.run_id)"
            + " executionId,BIN_TO_UUID(c.project_id) projectId,BIN_TO_UUID(c.run_id)"
            + " runId,BIN_TO_UUID(r.actor_id) actorId,t.trigger_source"
            + " triggerSource,c.provider,c.period_start periodStart,c.model,c.connection_version"
            + " connectionVersion,COUNT(*) attempts,SUM(c.state='COMPLETE')"
            + " completedCalls,SUM(c.state='COMPLETE' AND c.error_code IS NULL)"
            + " successfulCalls,SUM(c.state='COMPLETE' AND c.error_code IS NOT NULL)"
            + " failedCalls,SUM(c.state='PENDING') pendingCalls,SUM(c.state='UNKNOWN')"
            + " unknownCalls,SUM(c.state='NOT_SENT') notSentCalls,SUM(c.usage_state='KNOWN')"
            + " knownUsageCalls,SUM(c.usage_state='PARTIAL')"
            + " partialUsageCalls,SUM(c.usage_state='UNKNOWN')"
            + " unknownUsageCalls,SUM(c.input_tokens) inputTokens,SUM(c.output_tokens)"
            + " outputTokens,SUM(c.elapsed_millis) elapsedMillis FROM team_model_usage c JOIN"
            + " team_model_execution t ON t.run_id=c.run_id JOIN agent_run r ON r.id=c.run_id WHERE"
            + " c.workspace_id=? AND c.period_start=?"
            + filter
            + " GROUP BY"
            + " c.project_id,c.run_id,r.actor_id,t.trigger_source,c.provider,c.period_start,c.model,c.connection_version"
            + " UNION ALL SELECT"
            + " 'EMBEDDING',BIN_TO_UUID(e.operation_id),BIN_TO_UUID(e.project_id),NULL,BIN_TO_UUID(e.actor_id),IF(e.purpose='document','INDEX',IF(e.actor_id"
            + " IS NULL,'AUTOMATIC_QUERY','QUERY')),'DASHSCOPE',CAST(DATE_FORMAT(e.created_at,'%Y-%m-01')"
            + " AS DATE),s.model,e.connection_version,COUNT(*),SUM(e.state='COMPLETE'),SUM(e.state='COMPLETE'"
            + " AND e.error_code IS NULL),SUM(e.state='COMPLETE' AND e.error_code IS NOT"
            + " NULL),SUM(e.state='PENDING'),SUM(e.state='UNKNOWN'),SUM(e.state='NOT_SENT'),SUM(e.usage_state='KNOWN'),0,SUM(e.usage_state='UNKNOWN'),SUM(e.input_tokens),NULL,SUM(e.duration_ms)"
            + " FROM team_embedding_call e JOIN team_embedding_space s ON s.config_id=e.config_id"
            + " WHERE e.workspace_id=? AND e.created_at>=? AND e.created_at<DATE_ADD(?,INTERVAL 1"
            + " MONTH)"
            + (p == null ? "" : " AND e.project_id=?")
            + " GROUP BY"
            + " e.operation_id,e.project_id,e.actor_id,e.purpose,s.model,e.connection_version,CAST(DATE_FORMAT(e.created_at,'%Y-%m-01')"
            + " AS DATE)) usage_rows ORDER BY executionId LIMIT ?",
        args.toArray());
  }
}
