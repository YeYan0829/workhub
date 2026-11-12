package dev.workhub.agent;

import java.util.Map;

/** Server-owned model/endpoint/protocol allowlist. No provider is selected by a tool argument. */
public enum AgentProvider {
  GLM("glm-4.7","https://api.z.ai/api/paas/v4/chat/completions","WORKHUB_GLM_API_KEY"),
  GLM53("glm-5.3","https://api.z.ai/api/paas/v4/chat/completions","WORKHUB_GLM_API_KEY"),
  QWEN("qwen3.7-plus","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions","DASHSCOPE_API_KEY"),
  SEED("doubao-seed-2-1-pro-260628","https://ark.cn-beijing.volces.com/api/v3/chat/completions","ARK_API_KEY");
  public final String model,endpoint,keyVariable;
  AgentProvider(String model,String endpoint,String keyVariable){this.model=model;this.endpoint=endpoint;this.keyVariable=keyVariable;}
  public static AgentProvider forModel(String model){for(var p:values())if(p.model.equals(model))return p;throw new IllegalArgumentException("Unsupported server model contract");}
  public Map<String,Object> extras(){return this==GLM53?Map.of("thinking",Map.of("type","enabled","clear_thinking",true),"reasoning_effort","low")
      :this==QWEN?Map.of("enable_thinking",false):Map.of("thinking",Map.of("type","disabled"));}
  public String protocol(){return (this==GLM || this==GLM53)?"chat-completions-v4":this==QWEN?"dashscope-compatible-v1":"ark-chat-v3";}
}
