package dev.workhub.agent;

import dev.workhub.common.ApiException;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.*;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name="workhub.agent.enabled",havingValue="true")
public class GlmChatModel implements AgentModel {
  private final AgentSettings c;
  public record Wire(String phase,int status,String body,long elapsedMillis) {}
  private final java.util.function.Consumer<Wire> observer;
  @org.springframework.beans.factory.annotation.Autowired
  public GlmChatModel(AgentSettings c) { this(c,null); }
  /** Optional evidence sink; REQUEST observes prepared bytes before admission, not proof of transport. Authentication headers are never exposed. */
  public GlmChatModel(AgentSettings c,java.util.function.Consumer<Wire> observer) { this.c=c;this.observer=observer; }
  public Reply call(List<Message> messages,List<FunctionTool> tools,long remainingMillis) {
    return call(messages,tools,remainingMillis,()->{});
  }
  @Override public Reply call(List<Message> messages,List<FunctionTool> tools,long remainingMillis,Runnable beforeSend) {
    long began=System.nanoTime();var timing=new LinkedHashMap<String,Long>();long[] headersAt={0},sentAt={0};
    c.available();
    if(remainingMillis<1) throw new ApiException(503,"AGENT_TIMEOUT","分析时间预算耗尽");
    var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    long executionDeadline=began+remainingMillis*1_000_000L;
    var uri=java.net.URI.create(c.endpoint);
    var api=OpenAiApi.builder().baseUrl(uri.getScheme()+"://"+uri.getAuthority())
        .completionsPath(uri.getPath()).apiKey(c.key)
        .responseErrorHandler(new org.springframework.web.client.ResponseErrorHandler(){
          public boolean hasError(org.springframework.http.client.ClientHttpResponse r)throws java.io.IOException{return r.getStatusCode().isError();}
          public void handleError(org.springframework.http.client.ClientHttpResponse r)throws java.io.IOException{
            int status=r.getStatusCode().value();
            if(c.comparison() && status==404) {
              // Keep only an allowlisted provider code; never retain its account-bearing error message.
              try {
                var error=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getBody().readNBytes(8192)).path("error").path("code").asText();
                String code=switch(error) {
                  case "ModelNotOpen"->"MODEL_SERVICE_NOT_OPEN";
                  case "InvalidEndpointOrModel.NotFound"->"MODEL_ENDPOINT_NOT_FOUND";
                  case "InvalidEndpointOrModel.ModelIDAccessDisabled"->"MODEL_ID_ACCESS_DISABLED";
                  default->null;
                };
                if(code!=null)throw new ApiException(503,code,"模型服务未开通或不可访问");
              }catch(ApiException e){throw e;}catch(java.io.IOException ignored){}
            }
            throw new ModelHttpFailure(status,r.getHeaders().getFirst("Retry-After"),java.time.Instant.now());
          }
        })
        .restClientBuilder(RestClient.builder().requestFactory((target,method)->{
          // Interceptor body encoding and any evidence observer have already completed.
          // The underlying request factory is reached only at the final execution step.
          long ready=System.nanoTime();timing.put("clientSetupMillis",(ready-began)/1_000_000);
          beforeSend.run();
          long left=(executionDeadline-System.nanoTime())/1_000_000;
          if(left<=0 || Thread.currentThread().isInterrupted())throw new AgentModel.UnsentFailure("AGENT_TIMEOUT","发送前执行期限已耗尽");
          factory.setReadTimeout(Duration.ofMillis(Math.min(AgentSettings.MAX_REQUEST_MILLIS,left)));
          timing.put("sendAdmissionMillis",(System.nanoTime()-ready)/1_000_000);
          sentAt[0]=System.nanoTime();timing.put("httpSentAtEpochMillis",System.currentTimeMillis());
          return factory.createRequest(target,method);
        }).requestInterceptor((request,body,execution)->{
          if(observer!=null)observer.accept(new Wire("REQUEST",0,new String(body,java.nio.charset.StandardCharsets.UTF_8),(System.nanoTime()-began)/1_000_000));
          var response=execution.execute(request,body);headersAt[0]=System.nanoTime();timing.put("httpUntilHeadersMillis",(headersAt[0]-sentAt[0])/1_000_000);
          if(observer==null)return response;
          var bytes=response.getBody().readNBytes(1_000_001);
          if(bytes.length>1_000_000){response.close();throw new ApiException(503,"MODEL_RESPONSE_LIMIT","模型响应超出限制");}
          int status=response.getStatusCode().value();
          observer.accept(new Wire("RESPONSE",status,status>=400?"{\"httpStatus\":"+status+"}":new String(bytes,java.nio.charset.StandardCharsets.UTF_8),(System.nanoTime()-began)/1_000_000));
          return new org.springframework.http.client.ClientHttpResponse(){
            public org.springframework.http.HttpStatusCode getStatusCode()throws java.io.IOException{return response.getStatusCode();}
            public String getStatusText()throws java.io.IOException{return response.getStatusText();}
            public org.springframework.http.HttpHeaders getHeaders(){return response.getHeaders();}
            public java.io.InputStream getBody(){return new java.io.ByteArrayInputStream(bytes);}
            public void close(){response.close();}
          };
        })).build();
    var options=OpenAiChatOptions.builder().model(c.model).temperature(0.2)
        .maxTokens(AgentSettings.MAX_OUTPUT).internalToolExecutionEnabled(false)
        .extraBody(c.provider.extras()).build();
    var references=new LinkedHashSet<String>();
    for(var message:messages)if(message instanceof org.springframework.ai.chat.messages.ToolResponseMessage t)for(var response:t.getResponses()) {
      try { for(var ref:new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.responseData()).path("citations")) {
        String id=ref.path("id").asText();if(id.matches("S[1-9][0-9]*"))references.add(id);
      }}catch(java.io.IOException e){throw new ApiException(503,"AGENT_SERIALIZATION_ERROR","来源编号无法读取");}
    }
    // Only server-created Message metadata carries this catalog; user text cannot supply it.
    for(var message:messages)if(message.getMetadata().get("workhubSourceReferences") instanceof java.util.Collection<?> ids)
      for(var id:ids)if(id instanceof String value && value.matches("S[1-9][0-9]*"))references.add(value);
    // Keep GLM-5.3's native tool response channel available during investigation.
    // The final text shape is still checked by AgentAnswerSchema, including repairs.
    if(c.provider!=AgentProvider.GLM53 || tools.isEmpty())
      options.setResponseFormat(c.comparison() && messages.stream().noneMatch(m->Boolean.TRUE.equals(m.getMetadata().get("workhubTriage"))||Boolean.TRUE.equals(m.getMetadata().get("workhubCompaction"))) && tools.stream().noneMatch(t->t.getFunction().getName().equals("read_conversation_messages"))?AgentAnswerSchema.format(references):ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    if(!tools.isEmpty()) { options.setTools(tools); options.setToolChoice("auto"); }
    // JSON mode controls syntax; Java still validates shape, source IDs and kinds.
    var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
        .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    try {
      var r=model.call(new Prompt(messages,options));
      if(r==null || r.getResults().size()!=1 || r.getMetadata().getUsage()==null)
        throw new ApiException(503,"GLM_INVALID_RESPONSE","模型响应不完整");
      var g=r.getResult(); var usage=r.getMetadata().getUsage();
      timing.put("bodyReadAndDecodeMillis",headersAt[0]==0?0:(System.nanoTime()-headersAt[0])/1_000_000);
      return new Reply(g.getOutput(),usage.getPromptTokens(),usage.getCompletionTokens(),
          Objects.toString(g.getMetadata().getFinishReason(),"").toLowerCase(Locale.ROOT),r.getMetadata().getModel(),Map.copyOf(timing));
    } catch(RestClientResponseException e) {
      throw new ModelHttpFailure(e.getStatusCode().value(),e.getResponseHeaders()==null?null:e.getResponseHeaders().getFirst("Retry-After"),java.time.Instant.now());
    } catch(ApiException e) { throw e; }
    catch(RuntimeException e) {
      for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
        if(cause instanceof ApiException a)throw a;
        if(cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)
          throw new ApiException(503,"GLM_TIMEOUT","模型调用超时");
      }
      // Spring's JDK body timeout closes the stream; the resulting IOException may
      // carry no HttpTimeoutException. The exhausted local deadline is still known.
      if((System.nanoTime()-began)/1_000_000>=Math.min(AgentSettings.MAX_REQUEST_MILLIS,remainingMillis))
        throw new ApiException(503,"GLM_TIMEOUT","模型调用超时");
      throw new ApiException(503,"GLM_TRANSPORT_ERROR","模型调用失败或超时");
    }
  }
}
