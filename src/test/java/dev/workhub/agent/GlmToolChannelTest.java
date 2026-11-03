package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;

class GlmToolChannelTest {
  @Test void glm53InvestigationUsesNativeToolsAndSynthesisKeepsJson() throws Exception {
    try (var server=new MockWebServer()) {
      server.start();
      var settings=new AgentSettings(true,false,server.url("/chat").toString(),"local-key","glm-5.3","local",BigDecimal.ZERO,0,0);
      var model=new GlmChatModel(settings);var mapper=new ObjectMapper();
      var definition=new FunctionTool(new FunctionTool.Function("Read project tasks","list_tasks",Map.of("type","object","properties",Map.of()),null));
      server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("""
        {"id":"local","model":"glm-5.3","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call1","type":"function","function":{"name":"list_tasks","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":10}}
        """));
      var response=model.call(List.of(new UserMessage("查项目任务")),List.of(definition),10000);
      assertThat(response.message().getToolCalls()).hasSize(1);
      var request=mapper.readTree(server.takeRequest().getBody().readUtf8());
      assertThat(request.at("/tools/0/function/name").asText()).isEqualTo("list_tasks");
      assertThat(request.path("tool_choice").asText()).isEqualTo("auto");
      assertThat(request.hasNonNull("response_format")).isFalse();
      server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("""
        {"id":"local","model":"glm-5.3","choices":[{"index":0,"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":10}}
        """));
      model.call(List.of(new UserMessage("综合已提供资料")),List.of(),10000);
      request=mapper.readTree(server.takeRequest().getBody().readUtf8());
      assertThat(request.at("/response_format/type").asText()).isEqualTo("json_object");
    }
  }
}
