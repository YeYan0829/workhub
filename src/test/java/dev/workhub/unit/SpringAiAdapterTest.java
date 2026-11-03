package dev.workhub.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.contract.ModelContractSupport;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SpringAiAdapterTest {
    private MockWebServer server;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {server=new MockWebServer();server.start();}
    @AfterEach void stop() throws Exception {server.shutdown();}
    private void response(String body) { server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body)); }
    private String base() {return server.url("/").toString();}
    private String completion(String content) throws Exception {
        return "{\"id\":\"p0\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-chat\","
                +"\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"+json.writeValueAsString(content)+"},\"finish_reason\":\"stop\"}],"
                +"\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
    @Test void basicChatUsesRealSpringAiSerializationAgainstLocalHttp() throws Exception {
        response(completion("合同通过"));
        var model=ModelContractSupport.chat(ModelContractSupport.api(base(),"fixture-only",1000),"fixture-chat");
        assertThat(model.call("Reply briefly")).isEqualTo("合同通过");
        var request=server.takeRequest(2,TimeUnit.SECONDS);assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(json.readTree(request.getBody().readUtf8()).path("model").asText()).isEqualTo("fixture-chat");
    }
    @Test void jsonSchemaIsSentAndStructuredResponseParsed() throws Exception {
        response(completion("{\"ok\":true}"));
        var schema=ResponseFormat.JsonSchema.builder().name("contract").strict(true)
                .schema("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}").build();
        var options=OpenAiChatOptions.builder().responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schema).build()).build();
        var model=ModelContractSupport.chat(ModelContractSupport.api(base(),"fixture-only",1000),"fixture-chat");
        var result=model.call(new Prompt("Return ok",options));
        assertThat(json.readTree(result.getResult().getOutput().getText()).path("ok").asBoolean()).isTrue();
        var request=json.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(request.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(request.at("/response_format/json_schema/strict").asBoolean()).isTrue();
    }
    @Test void toolRequestIsReturnedWithoutExecutingCallback() throws Exception {
        response("""
          {"id":"p0","object":"chat.completion","created":1,"model":"fixture-chat",
           "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"fixture_lookup","arguments":"{}"}}
           ]},"finish_reason":"tool_calls"}]}
          """);
        AtomicInteger calls=new AtomicInteger();
        ToolCallback tool=new ToolCallback() {
            public ToolDefinition getToolDefinition() {return ToolDefinition.builder().name("fixture_lookup").description("Read a fixed fixture").inputSchema("{\"type\":\"object\",\"properties\":{}}").build();}
            public String call(String input) {calls.incrementAndGet();return "fixture";}
        };
        var options=OpenAiChatOptions.builder().toolCallbacks(tool).internalToolExecutionEnabled(false).build();
        var model=ModelContractSupport.chat(ModelContractSupport.api(base(),"fixture-only",1000),"fixture-chat");
        var response=model.call(new Prompt("Look up fixture",options));
        assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
        assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("fixture_lookup");
        assertThat(calls).hasValue(0);
        var request=json.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(request.at("/tools/0/function/name").asText()).isEqualTo("fixture_lookup");
    }
    @Test void embeddingDimensionAndValuesSurviveAdapter() throws Exception {
        response("""
           {"object":"list","model":"fixture-embedding","data":[{"object":"embedding","index":0,"embedding":[1.0,0.0,0.0]}],"usage":{"prompt_tokens":1,"total_tokens":1}}
           """);
        var model=ModelContractSupport.embedding(ModelContractSupport.api(base(),"fixture-only",1000),"fixture-embedding",3);
        assertThat(model.embed("contract")).containsExactly(1f,0f,0f);
        var request=server.takeRequest(2,TimeUnit.SECONDS);assertThat(request.getPath()).isEqualTo("/v1/embeddings");
        assertThat(json.readTree(request.getBody().readUtf8()).path("dimensions").asInt()).isEqualTo(3);
    }
    @Test void httpErrorIsVisibleWithoutHiddenRetries() {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("Content-Type","application/json")
                .setBody("{\"error\":{\"message\":\"fixture denied\",\"type\":\"authentication_error\"}}"));
        var model=ModelContractSupport.chat(ModelContractSupport.api(base(),"fixture-only",500),"fixture-chat");
        assertThatThrownBy(() -> model.call("contract")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @Test void slowProviderHitsConfiguredReadTimeout() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(completion("late")).setBodyDelay(2,TimeUnit.SECONDS));
        var model=ModelContractSupport.chat(ModelContractSupport.api(base(),"fixture-only",150),"fixture-chat");
        long start=System.nanoTime();
        assertThatThrownBy(() -> model.call("contract")).isInstanceOf(RuntimeException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1800);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
