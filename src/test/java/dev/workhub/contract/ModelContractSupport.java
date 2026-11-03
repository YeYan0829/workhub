package dev.workhub.contract;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Adapter construction only: no agent loop, no automatically executed tools. */
public final class ModelContractSupport {
    private ModelContractSupport() { }
    public static OpenAiApi api(String baseUrl,String key,int timeoutMs) {
        var requestFactory=new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);requestFactory.setReadTimeout(timeoutMs);
        return OpenAiApi.builder().baseUrl(baseUrl).apiKey(key)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
    }
    public static OpenAiChatModel chat(OpenAiApi api,String model) {
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
    public static OpenAiEmbeddingModel embedding(OpenAiApi api,String model,int dimension) {
        return new OpenAiEmbeddingModel(api,MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(model).dimensions(dimension).build(),
                RetryTemplate.builder().maxAttempts(1).build());
    }
}
