package dev.workhub.agent;

import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;

public interface AgentModel {
  /** A synchronous transport adapter has positively established that no HTTP request started.
   * Never use this for a socket failure, a missing response, or persisted crash recovery state.
   */
  class UnsentFailure extends dev.workhub.common.ApiException {
    public UnsentFailure(String code,String message){super(503,code,message);}
  }
  record Reply(AssistantMessage message, long inputTokens, long outputTokens, String finishReason, String model, Map<String,Long> timings) {
    public Reply(AssistantMessage message,long inputTokens,long outputTokens,String finishReason,String model){this(message,inputTokens,outputTokens,finishReason,model,Map.of());}
  }
  Reply call(List<Message> messages, List<FunctionTool> tools, long remainingMillis);
  /** Real HTTP adapters invoke this immediately before transport, after client setup. */
  default Reply call(List<Message> messages,List<FunctionTool> tools,long remainingMillis,Runnable beforeSend) {
    beforeSend.run();return call(messages,tools,remainingMillis);
  }
}
