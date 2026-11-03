package dev.workhub.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelProviderSmoke {
    @Test void twoMinimalChatsAndOneEmbeddingOnlyAfterExplicitOptIn() throws Exception {
        assumeTrue(!ContractSupport.env("MODEL_API_KEY","").isBlank(),"BLOCKED_BY_CREDENTIAL: MODEL_API_KEY");
        assumeTrue(ContractSupport.env("ALLOW_PAID_MODEL_SMOKE","false").equals("true"),"BLOCKED_BY_PAID_CALL_APPROVAL");
        var api=ModelContractSupport.api(ContractSupport.required("MODEL_BASE_URL"),ContractSupport.required("MODEL_API_KEY"),10000);
        var model=ModelContractSupport.chat(api,ContractSupport.required("MODEL_CHAT_ID"));
        var schema=ResponseFormat.JsonSchema.builder().name("phase0").strict(true)
            .schema("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}").build();
        var options=OpenAiChatOptions.builder().maxTokens(64).responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schema).build()).build();
        var response=model.call(new Prompt("Return ok=true as JSON.",options));
        assertThat(new ObjectMapper().readTree(response.getResult().getOutput().getText()).path("ok").asBoolean()).isTrue();
        AtomicInteger calls=new AtomicInteger();
        ToolCallback callback=new ToolCallback() {
            public ToolDefinition getToolDefinition() {return ToolDefinition.builder().name("phase0_lookup").description("Read the phase0 fixture")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();}
            public String call(String input) {calls.incrementAndGet();return "fixture";}
        };
        var toolOptions=OpenAiChatOptions.builder().maxTokens(64).toolCallbacks(callback).toolChoice("required").internalToolExecutionEnabled(false).build();
        var toolResponse=model.call(new Prompt("Call phase0_lookup.",toolOptions));
        assertThat(toolResponse.getResult().getOutput().getToolCalls()).hasSize(1);
        assertThat(calls).hasValue(0);
        int dimension=Integer.parseInt(ContractSupport.required("MODEL_EMBEDDING_DIMENSION"));
        float[] embedding=ModelContractSupport.embedding(api,ContractSupport.required("MODEL_EMBEDDING_ID"),dimension).embed("Phase 0 技术合同");
        assertThat(embedding).hasSize(dimension);
        for(float value:embedding)assertThat(Float.isFinite(value)).isTrue();
        System.out.println("LIVE_MODEL: schema, tool-return-only and embedding passed; dimension="+dimension);
    }
}
