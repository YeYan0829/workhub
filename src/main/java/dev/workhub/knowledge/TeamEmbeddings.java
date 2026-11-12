package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.agent.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.BusinessJobs;
import dev.workhub.onboarding.Workspaces;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Team credentials, immutable spaces, and project routing. No network or price configuration. */
@Service
public class TeamEmbeddings {
  public static final String MODEL = "text-embedding-v4";
  public static final List<Integer> DIMENSIONS = List.of(64, 128, 256, 512, 768, 1024, 1536, 2048);

  private final JdbcTemplate db;
  private final Json json;
  private final Workspaces workspaces;
  private final ProjectAccess access;
  private final ModelKeyCipher cipher;
  private final BusinessJobs jobs;
  private final KnowledgeReads reads;
  private final boolean enabled;
  private final String fixture;

  public TeamEmbeddings(
      JdbcTemplate db,
      Json json,
      Workspaces workspaces,
      ProjectAccess access,
      ModelKeyCipher cipher,
      BusinessJobs jobs,
      KnowledgeReads reads,
      @Value("${workhub.embedding.enabled:false}") boolean enabled,
      @Value("${workhub.embedding.legacy-mode:false}") boolean legacy,
      @Value("${workhub.retrieval.enabled:false}") boolean retrieval,
      @Value("${workhub.team-model.embedding-fixture-endpoint:}") String fixture) {
    this.db = db;
    this.json = json;
    this.workspaces = workspaces;
    this.access = access;
    this.cipher = cipher;
    this.jobs = jobs;
    this.reads = reads;
    this.enabled = enabled && !legacy && retrieval;
    this.fixture = fixture;
    if (!fixture.isBlank()) {
      var u = URI.create(fixture);
      if (!"http".equals(u.getScheme())
          || !Set.of("localhost", "127.0.0.1", "::1", "[::1]")
              .contains(Objects.toString(u.getHost(), ""))
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null)
        throw new IllegalArgumentException("Embedding fixture requires server-owned loopback HTTP");
    }
  }

  public record ConnectionUpdate(
      long expectedVersion,
      String model,
      int dimensions,
      String queryInstruction,
      boolean enabled,
      boolean useGenerationKey,
      double requestsPerSecond,
      int burst,
      String apiKey,
      String providerWorkspaceId) {
    @Override
    public String toString() {
      return "EmbeddingConnectionUpdate[credentials redacted]";
    }
  }

  public record Space(
      int configId,
      UUID workspaceId,
      String model,
      int dimensions,
      String queryInstruction,
      String identity,
      String indexName) {}

  public record ConnectionView(
      long version,
      boolean enabled,
      boolean useGenerationKey,
      boolean hasKey,
      String keyMarker,
      Space defaultSpace,
      ModelAdmission.Quota quota,
      String configurationIssue,
      String providerWorkspaceId) {}

  public record PolicyUpdate(long expectedVersion, boolean enabled, Integer configId) {}

  public record Policy(
      long version,
      boolean enabled,
      Integer activeConfigId,
      Integer buildingConfigId,
      long eligibleSources,
      long readySources,
      long pendingJobs,
      long failedJobs,
      List<Space> availableSpaces) {}

  public record Version(long expectedVersion) {}

  public record Ticket(
      UUID project,
      UUID workspace,
      int config,
      long connectionVersion,
      long policyVersion,
      Long generationVersion,
      UUID actor,
      ModelAdmission.Quota quota,
      ActorContext authorization) {}

  static long n(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).longValue();
  }

  static Integer integer(Object o) {
    return o == null ? null : ((Number) o).intValue();
  }

  private void expected(long actual, long requested) {
    if (actual != requested) throw ApiException.conflict("MODEL_CONFIG_CONFLICT", actual);
  }

  public UUID workspace(UUID p) {
    var rows =
        db.query(
            "SELECT workspace_id FROM project WHERE id=?",
            (r, n) -> read(r, "workspace_id"),
            bytes(p));
    if (rows.isEmpty()) throw ApiException.forbidden();
    return rows.get(0);
  }

  private Map<String, Object> connection(UUID w) {
    var rows =
        db.queryForList("SELECT * FROM team_embedding_connection WHERE workspace_id=?", bytes(w));
    if (rows.isEmpty())
      throw new ApiException(503, "EMBEDDING_NOT_CONFIGURED", "团队尚未配置 Embedding 连接");
    return rows.get(0);
  }

  private Map<String, Object> generation(UUID w) {
    var rows =
        db.queryForList("SELECT * FROM team_model_connection WHERE workspace_id=?", bytes(w));
    if (rows.isEmpty()
        || !"DASHSCOPE".equals(rows.get(0).get("provider"))
        || !Boolean.TRUE.equals(rows.get(0).get("enabled")))
      throw new ApiException(503, "EMBEDDING_GENERATION_CONNECTION_INVALID", "复用凭据需要启用的百炼生成连接");
    return rows.get(0);
  }

  public static String scope(UUID w) {
    return "team-embedding-" + w;
  }

  private ModelAdmission.Quota quota(UUID w, Map<String, Object> c) {
    var g = Boolean.TRUE.equals(c.get("use_generation_key")) ? generation(w) : c;
    return new ModelAdmission.Quota(
        g == c ? scope(w) : TeamModels.scope(w),
        n(g, "version"),
        ((Number) g.get("request_rate")).doubleValue(),
        integer(g.get("request_burst")));
  }

  public Space space(UUID w, int config) {
    var rows =
        db.queryForList(
            "SELECT * FROM team_embedding_space WHERE workspace_id=? AND config_id=?",
            bytes(w),
            config);
    if (rows.isEmpty()) throw ApiException.forbidden();
    var r = rows.get(0);
    return new Space(
        config,
        w,
        (String) r.get("model"),
        integer(r.get("dimensions")),
        (String) r.get("query_instruction"),
        (String) r.get("identity_hash"),
        (String) r.get("index_name"));
  }

  public Space projectSpace(UUID p, int config) {
    return space(workspace(p), config);
  }

  private ConnectionView view(UUID w) {
    var c = connection(w);
    ModelAdmission.Quota q = null;
    String issue = null;
    try {
      configuredEndpoint(c);
      q = quota(w, c);
    } catch (ApiException error) {
      issue = error.code;
    }
    return new ConnectionView(
        n(c, "version"),
        Boolean.TRUE.equals(c.get("enabled")),
        Boolean.TRUE.equals(c.get("use_generation_key")),
        true,
        "********",
        space(w, integer(c.get("default_config_id"))),
        q,
        issue,
        (String) c.get("provider_workspace_id"));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> catalog(ActorContext a, UUID w) {
    workspaces.admin(a, w);
    return Map.of("model", MODEL, "dimensions", DIMENSIONS, "region", "北京");
  }

  @Transactional(readOnly = true)
  public ConnectionView getConnection(ActorContext a, UUID w) {
    workspaces.admin(a, w);
    return view(w);
  }

  @Transactional(readOnly = true)
  public List<Space> spaces(ActorContext a, UUID w) {
    workspaces.admin(a, w);
    return db
        .queryForList(
            "SELECT config_id FROM team_embedding_space WHERE workspace_id=? ORDER BY config_id"
                + " DESC LIMIT 100",
            bytes(w))
        .stream()
        .map(r -> space(w, integer(r.get("config_id"))))
        .toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ConnectionView updateConnection(ActorContext a, UUID w, ConnectionUpdate u) {
    workspaces.lock(w);
    workspaces.admin(a, w);
    var old =
        db.queryForList(
            "SELECT * FROM team_embedding_connection WHERE workspace_id=? FOR UPDATE", bytes(w));
    long version = old.isEmpty() ? 0 : n(old.get(0), "version");
    expected(version, u.expectedVersion());
    nativeEndpoint(u.providerWorkspaceId());
    if (!MODEL.equals(u.model())
        || !DIMENSIONS.contains(u.dimensions())
        || u.queryInstruction() == null
        || u.queryInstruction().length() > 1000
        || !Double.isFinite(u.requestsPerSecond())
        || u.requestsPerSecond() < 0.001
        || u.requestsPerSecond() > 10000
        || u.burst() < 1
        || u.burst() > 10000) throw ApiException.invalid("Embedding 模型、维度、指令或速率无效");
    if (u.useGenerationKey() && u.apiKey() != null) throw ApiException.invalid("复用生成连接时不要提交独立密钥");
    String key = null;
    if (u.useGenerationKey()) {
      if (u.enabled()) generation(w);
    } else {
      key =
          u.apiKey() == null
              ? (old.isEmpty() ? null : (String) old.get(0).get("key_cipher"))
              : cipher.encrypt(w, u.apiKey());
      if (key == null) throw ApiException.invalid("独立连接首次配置需要密钥");
    }
    var contract = new TreeMap<String, Object>();
    contract.put("provider", "DASHSCOPE");
    contract.put("providerRegion", "cn-beijing");
    contract.put("protocol", "dashscope-text-embedding");
    contract.put("model", MODEL);
    contract.put("modelRevision", "workhub-dashscope-v4-contract-1");
    contract.put("dimensions", u.dimensions());
    contract.put("queryInstruction", u.queryInstruction());
    contract.put("documentInput", "exact-canonical-chunk-v1");
    contract.put("encoding", "l2-float32-le-v1");
    contract.put("chunker", "lines-2000-utf16-overlap160-v1");
    contract.put("analyzer", "identifier-cjk-bigram-v1");
    String identity = Inputs.hash(json.write(contract));
    var existing =
        db.queryForList(
            "SELECT config_id FROM team_embedding_space WHERE workspace_id=? AND identity_hash=?",
            bytes(w),
            identity);
    int config;
    if (existing.isEmpty()) {
      long next =
          db.queryForObject(
              "SELECT next_config FROM team_embedding_sequence WHERE id=1 FOR UPDATE", Long.class);
      next =
          Math.max(
              next,
              db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM retrieval_config", Long.class));
      if (next >= Integer.MAX_VALUE)
        throw new ApiException(503, "EMBEDDING_SPACE_LIMIT", "向量配置编号已耗尽");
      config = (int) next;
      db.update("UPDATE team_embedding_sequence SET next_config=? WHERE id=1", next + 1);
      db.update(
          "INSERT INTO retrieval_config(id,chunker,analyzer,identity_hash,model_contract) VALUES"
              + " (?,'lines-2000-utf16-overlap160-v1','identifier-cjk-bigram-v1',?,?)",
          config,
          identity,
          json.write(contract));
      db.update(
          "INSERT INTO"
              + " team_embedding_space(config_id,workspace_id,identity_hash,model,dimensions,query_instruction,index_name,created_by)"
              + " VALUES (?,?,?,?,?,?,?,?)",
          config,
          bytes(w),
          identity,
          MODEL,
          u.dimensions(),
          u.queryInstruction(),
          "workhub-team-vector-" + w.toString().replace("-", "") + "-" + config,
          bytes(a.userId()));
    } else config = integer(existing.get(0).get("config_id"));
    db.update(
        "INSERT INTO"
            + " team_embedding_connection(workspace_id,version,enabled,use_generation_key,key_cipher,default_config_id,request_rate,request_burst,updated_by,provider_workspace_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " provider_workspace_id=VALUES(provider_workspace_id),version=VALUES(version),enabled=VALUES(enabled),use_generation_key=VALUES(use_generation_key),key_cipher=VALUES(key_cipher),default_config_id=VALUES(default_config_id),request_rate=VALUES(request_rate),request_burst=VALUES(request_burst),updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(6)",
        bytes(w),
        version + 1,
        u.enabled(),
        u.useGenerationKey(),
        key,
        config,
        u.requestsPerSecond(),
        u.burst(),
        bytes(a.userId()),
        u.providerWorkspaceId().toLowerCase(Locale.ROOT));
    workspaces.audit(
        a,
        w,
        null,
        "EMBEDDING_CONNECTION_UPDATED",
        Map.of(
            "version",
            version + 1,
            "configId",
            config,
            "enabled",
            u.enabled(),
            "keyReplaced",
            u.apiKey() != null,
            "useGenerationKey",
            u.useGenerationKey()));
    return view(w);
  }

  public List<Integer> routes(UUID p) {
    if (!enabled) return List.of();
    var r =
        db.queryForList(
            "SELECT * FROM project_embedding_policy WHERE project_id=? AND enabled", bytes(p));
    if (r.isEmpty()) return List.of();
    var out = new LinkedHashSet<Integer>();
    for (String k : List.of("active_config_id", "building_config_id"))
      if (r.get(0).get(k) != null) out.add(integer(r.get(0).get(k)));
    return List.copyOf(out);
  }

  public boolean routed(UUID p, int config) {
    return routes(p).contains(config);
  }

  public Integer active(UUID p) {
    var r =
        db.queryForList(
            "SELECT active_config_id FROM project_embedding_policy WHERE project_id=? AND enabled",
            bytes(p));
    return r.isEmpty() ? null : integer(r.get(0).get("active_config_id"));
  }

  private List<Space> projectSpaces(UUID p) {
    UUID w = workspace(p);
    return db
        .queryForList(
            "SELECT config_id FROM team_embedding_space WHERE workspace_id=? ORDER BY config_id"
                + " DESC LIMIT 100",
            bytes(w))
        .stream()
        .map(r -> space(w, integer(r.get("config_id"))))
        .toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Policy policy(ActorContext a, UUID p) {
    access.read(a, p);
    var rows =
        db.queryForList("SELECT * FROM project_embedding_policy WHERE project_id=?", bytes(p));
    if (rows.isEmpty())
      return new Policy(
          0, false, null, null, reads.status(a, p).eligible(), 0, 0, 0, projectSpaces(p));
    var r = rows.get(0);
    Integer active = integer(r.get("active_config_id")),
        building = integer(r.get("building_config_id"));
    int target = building != null ? building : active != null ? active : 1;
    var status = reads.status(a, p, target);
    return new Policy(
        n(r, "version"),
        Boolean.TRUE.equals(r.get("enabled")),
        active,
        building,
        status.eligible(),
        reads.vectorCoverage(a, p, target),
        status.pending(),
        status.failed(),
        projectSpaces(p));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Policy updatePolicy(ActorContext a, UUID p, PolicyUpdate u) {
    var project = access.lock(a, p);
    access.maintainer(a, p);
    var old = policy(a, p);
    expected(old.version(), u.expectedVersion());
    Integer target = null;
    if (u.enabled()) {
      target =
          u.configId() == null
              ? integer(connection(project.workspaceId()).get("default_config_id"))
              : u.configId();
      space(project.workspaceId(), target);
      if (!enabled) throw new ApiException(503, "EMBEDDING_DISABLED", "部署尚未启用 Embedding");
    }
    Integer building = u.enabled() && !Objects.equals(old.activeConfigId(), target) ? target : null;
    db.update(
        "INSERT INTO"
            + " project_embedding_policy(project_id,workspace_id,version,enabled,active_config_id,building_config_id,updated_by)"
            + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " version=VALUES(version),enabled=VALUES(enabled),building_config_id=VALUES(building_config_id),updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(6)",
        bytes(p),
        bytes(project.workspaceId()),
        old.version() + 1,
        u.enabled(),
        old.activeConfigId(),
        building,
        bytes(a.userId()));
    workspaces.audit(
        a,
        project.workspaceId(),
        p,
        "EMBEDDING_POLICY_UPDATED",
        Map.of(
            "version",
            old.version() + 1,
            "enabled",
            u.enabled(),
            "targetConfigId",
            target == null ? 0 : target));
    if (u.enabled()) enqueueRebuild(p, target, UUID.randomUUID(), null);
    return policy(a, p);
  }

  public UUID enqueueRebuild(UUID p, int config, UUID batch, UUID after) {
    return jobs.enqueue(
        "TEAM_EMBED_REBUILD",
        p,
        p,
        "team-embed-rebuild/" + batch + "/" + Objects.toString(after, "start"),
        Map.of(
            "configId", config, "batchId", batch, "after", after == null ? "" : after.toString()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Policy rebuild(ActorContext a, UUID p, Version u) {
    access.lock(a, p);
    access.maintainer(a, p);
    var v = policy(a, p);
    expected(v.version(), u.expectedVersion());
    if (!v.enabled()) throw ApiException.invalid("项目尚未启用 Embedding");
    int c = v.buildingConfigId() != null ? v.buildingConfigId() : v.activeConfigId();
    enqueueRebuild(p, c, UUID.randomUUID(), null);
    return policy(a, p);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
  public Policy activate(ActorContext a, UUID p, Version u) {
    access.lock(a, p);
    access.maintainer(a, p);
    var v = policy(a, p);
    expected(v.version(), u.expectedVersion());
    if (!v.enabled() || v.buildingConfigId() == null) throw ApiException.invalid("没有待切换的向量空间");
    if (v.readySources() != v.eligibleSources())
      throw new ApiException(409, "EMBEDDING_INDEX_NOT_READY", "新索引尚未追平当前资料");
    ticket(p, v.buildingConfigId(), a.userId());
    db.update(
        "UPDATE project_embedding_policy SET"
            + " active_config_id=building_config_id,building_config_id=NULL,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6)"
            + " WHERE project_id=?",
        bytes(a.userId()),
        bytes(p));
    workspaces.audit(
        a,
        workspace(p),
        p,
        "EMBEDDING_INDEX_ACTIVATED",
        Map.of("configId", v.buildingConfigId(), "version", v.version() + 1));
    return policy(a, p);
  }

  public Ticket ticket(UUID p, int config, UUID actor) {
    return ticketForActor(p, config, actor == null ? null : new ActorContext(actor));
  }

  public Ticket ticketForActor(UUID p, int config, ActorContext authorization) {
    UUID actor =
        authorization == null || authorization.analysisScope() != null
            ? null
            : authorization.userId();
    if (!enabled) throw new ApiException(503, "EMBEDDING_DISABLED", "部署尚未启用 Embedding");
    UUID w = workspace(p);
    space(w, config);
    var rows =
        db.queryForList("SELECT * FROM project_embedding_policy WHERE project_id=?", bytes(p));
    if (rows.isEmpty() || !routed(p, config))
      throw new ApiException(503, "EMBEDDING_POLICY_CHANGED", "项目 Embedding 策略已变化");
    if (db.queryForObject("SELECT archived FROM project WHERE id=?", Boolean.class, bytes(p)))
      throw new ApiException(409, "PROJECT_ARCHIVED", "项目已归档");
    if (authorization != null) access.read(authorization, p);
    var c = connection(w);
    if (!Boolean.TRUE.equals(c.get("enabled")))
      throw new ApiException(503, "EMBEDDING_CONNECTION_DISABLED", "团队 Embedding 连接已禁用");
    configuredEndpoint(c);
    boolean shared = Boolean.TRUE.equals(c.get("use_generation_key"));
    return new Ticket(
        p,
        w,
        config,
        n(c, "version"),
        n(rows.get(0), "version"),
        shared ? n(generation(w), "version") : null,
        actor,
        quota(w, c),
        authorization);
  }

  public void verify(Ticket t) {
    var now = ticketForActor(t.project(), t.config(), t.authorization());
    if (now.connectionVersion() != t.connectionVersion()
        || now.policyVersion() != t.policyVersion()
        || !Objects.equals(now.generationVersion(), t.generationVersion()))
      throw new ApiException(503, "EMBEDDING_CONFIG_CHANGED", "Embedding 配置或授权已变化");
  }

  public String credential(Ticket t) {
    verify(t);
    var c = connection(t.workspace());
    return cipher.decrypt(
        t.workspace(),
        (String) (t.generationVersion() == null ? c : generation(t.workspace())).get("key_cipher"));
  }

  public static String nativeEndpoint(String providerWorkspaceId) {
    if (providerWorkspaceId == null
        || !providerWorkspaceId
            .toLowerCase(Locale.ROOT)
            .matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
      throw ApiException.invalid("需要百炼北京地域的业务空间 ID，不能提供 URL 或主机名");
    return "https://"
        + providerWorkspaceId.toLowerCase(Locale.ROOT)
        + ".cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
  }

  private String configuredEndpoint(Map<String, Object> connection) {
    try {
      return nativeEndpoint((String) connection.get("provider_workspace_id"));
    } catch (ApiException e) {
      throw new ApiException(503, "EMBEDDING_PROVIDER_WORKSPACE_REQUIRED", "请管理员配置百炼北京地域的业务空间 ID");
    }
  }

  public String endpoint(Ticket ticket) {
    return fixture.isBlank() ? configuredEndpoint(connection(ticket.workspace())) : fixture;
  }
}
