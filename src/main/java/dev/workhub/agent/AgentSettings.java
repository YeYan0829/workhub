package dev.workhub.agent;

import dev.workhub.common.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentSettings {
  public boolean legacyMode;
  public final boolean enabled, approved, fixture;
  public final String endpoint, key, model, authorization;
  public final AgentProvider provider;
  @org.springframework.beans.factory.annotation.Value("${workhub.agent.campaign-baseline:-1}") public long campaignBaseline=-1;
  @org.springframework.beans.factory.annotation.Value("${workhub.agent.campaign-limit:0}") public int campaignLimit=0;
  // Per-request input envelope; separate from supplier usage reporting.
  @Value("${workhub.agent.max-request-input-bound:256000}") public int maxRequestInputBound=256000;
  public final long budget, inputPrice, outputPrice;
  @Value("${workhub.model-admission.enabled:false}") public boolean sharedAdmission=false;
  @Value("${workhub.model-admission.scope:}") public String quotaScope="";
  @Value("${workhub.model-admission.requests-per-second:2}") public double requestRate=2;
  @Value("${workhub.model-admission.burst:1}") public int requestBurst=1;
  // One loop, one request count, one deadline. No investigation/final/repair quotas.
  public static final int MAX_ROUNDS = 20, MAX_OUTPUT = 4096;
  public static final int MAX_MILLIS = 300000, MAX_REQUEST_MILLIS = 60000;
  @org.springframework.beans.factory.annotation.Autowired
  public AgentSettings(
      @Value("${workhub.agent.enabled:false}") boolean enabled,
      @Value("${workhub.agent.paid-approved:false}") boolean approved,
      @Value("${workhub.agent.endpoint:https://api.z.ai/api/paas/v4/chat/completions}") String endpoint,
      @Value("${workhub.agent.api-key:}") String key,
      @Value("${workhub.agent.model:glm-4.7}") String model,
      @Value("${workhub.agent.authorization-id:}") String authorization,
      @Value("${workhub.agent.budget-usd:0}") BigDecimal budget,
      @Value("${workhub.agent.input-nanousd-per-token:600}") long inputPrice,
      @Value("${workhub.agent.output-nanousd-per-token:2200}") long outputPrice,
      @Value("${workhub.agent.budget-cny:0}") BigDecimal budgetCny,
      @Value("${workhub.agent.legacy-mode:false}") boolean legacyMode) {
    this.legacyMode=legacyMode;
    this.enabled=enabled; this.approved=approved; this.endpoint=endpoint; this.key=key;
    this.model=model; this.authorization=authorization; this.provider=AgentProvider.forModel(model);
    var uri=URI.create(endpoint);
    fixture=Set.of("localhost","127.0.0.1","::1","[::1]").contains(Objects.toString(uri.getHost(),""));
    if (enabled && legacyMode && (key.isBlank() || authorization.isBlank() || authorization.length()>100
        || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
        || !(fixture && "http".equals(uri.getScheme()) || endpoint.equals(provider.endpoint))
        || inputPrice<0 || outputPrice<0 || budget.signum()<0 || budgetCny.signum()<0))
      throw new IllegalArgumentException("Invalid provider contract; secrets are never logged");
    this.budget=!legacyMode?0:(!comparison()?budget:budgetCny).multiply(BigDecimal.valueOf(1_000_000_000L)).longValueExact();
    this.inputPrice=!legacyMode || fixture?0:Math.max(inputPrice,legacyInputPrice()); this.outputPrice=!legacyMode || fixture?0:Math.max(outputPrice,legacyOutputPrice());
  }
  public AgentSettings(boolean enabled,boolean approved,String endpoint,String key,String model,String authorization,BigDecimal budget,long inputPrice,long outputPrice,BigDecimal budgetCny) {
    this(enabled,approved,endpoint,key,model,authorization,budget,inputPrice,outputPrice,budgetCny,true);
  }
  public AgentSettings(boolean enabled,boolean approved,String endpoint,String key,String model,String authorization,BigDecimal budget,long inputPrice,long outputPrice) {
    this(enabled,approved,endpoint,key,model,authorization,budget,inputPrice,outputPrice,budget);
  }
  /** Generation runtime for team BYOK: monetary settings are not consulted. */
  public static AgentSettings teamRuntime(String endpoint, String key, String model) {
    return new AgentSettings(true, false, endpoint, key, model, "", BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, false);
  }
  // Frozen compatibility estimates for explicit legacy deployments/tests only.
  private long legacyInputPrice(){return switch(provider){case GLM->600;case GLM53->1400;case QWEN->2000;case SEED->6000;};}
  private long legacyOutputPrice(){return switch(provider){case GLM->2200;case GLM53->4400;case QWEN->8000;case SEED->30000;};}
  public boolean comparison(){return provider==AgentProvider.QWEN || provider==AgentProvider.SEED;}
  public String budgetTable(){return comparison()?"agent_provider_budget":"agent_budget";}
  public String callTable(){return comparison()?"agent_provider_call":"agent_model_call";}
  public String moneyColumn(String prefix){return prefix+(comparison()?"_nano_cny":"_nano_usd");}
  public void available() {
    if (!enabled) throw new ApiException(503,"AGENT_DISABLED","Project Agent 尚未启用");
    if (legacyMode && !fixture && (!approved || budget==0)) throw new ApiException(503,comparison()?"MODEL_BUDGET_NOT_APPROVED":"GLM_BUDGET_NOT_APPROVED","模型调用预算尚未授权");
  }
  public Map<String,Object> contract() {
    var contract = new TreeMap<String,Object>(billingContract());
    contract.put("runtimeVersion", "readonly-baseline-2");
    contract.put("modelAdmission",Map.of("enabled",sharedAdmission,"version","shared-model-2",
        "quotaScope",quotaScope,"requestsPerSecond",requestRate,"burst",requestBurst,
        "singleWaitMillis",15000,"runWaitMillis",30000,"permitMillis",500,"extraRetriesPerRun",2));
    contract.put("responseFormat", "native-tools-or-json-paragraphs");
    contract.put("toolPhaseFormat", provider==AgentProvider.GLM53?"native-tools":"json");
    contract.put("maxConsecutiveRejectedAnswers", 3);
    contract.put("maxModelCalls", MAX_ROUNDS);
    contract.put("maxMillis", MAX_MILLIS);
    contract.put("maxRequestMillis", MAX_REQUEST_MILLIS);
    contract.put("maxRequestInputBound", maxRequestInputBound);
    contract.put("maxOutputTokens", MAX_OUTPUT);
    contract.put("thinking", provider==AgentProvider.GLM53?"enabled":"disabled");
    if(provider==AgentProvider.GLM53){contract.put("reasoningEffort","low");contract.put("clearThinking",true);}
    contract.put("temperature", 0.2);
    return Map.copyOf(contract);
  }
  // Prompt/tool development must share the same cumulative monetary authorization.
  // The per-run contract above remains versioned independently.
  public Map<String,Object> billingContract() {
    if(comparison())return Map.of("model",model,"endpoint",endpoint,"protocol",provider.protocol(),"fixture",fixture,"currency","CNY");
    return Map.of("model",model,"endpoint",endpoint,"protocol","chat-completions-v4","fixture",fixture);
  }
}
