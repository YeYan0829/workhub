package dev.workhub.agent;

import static dev.workhub.agent.AgentContracts.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.workhub.common.ApiException;
import java.util.*;
import java.util.List;
import org.springframework.ai.openai.api.ResponseFormat;

/** Answer wire format; source identities are resolved by the server. */
public final class AgentAnswerSchema {
  private AgentAnswerSchema(){}
  public static Map<String,Object> schema(){
    return schema(List.of());
  }
  public static Map<String,Object> schema(java.util.Collection<String> references){
    var strings=Map.of("type","array","maxItems",12,"items",Map.of("type","string"));
    var citations=references.isEmpty()?strings:Map.of("type","array","maxItems",12,"items",Map.of("type","string","enum",List.copyOf(references)));
    var paragraph=Map.of("type","object","additionalProperties",false,"required",List.of("text","citations"),
        "properties",Map.of("text",Map.of("type","string","minLength",1,"maxLength",4000,"pattern",".*\\S.*","description","必须含非空白字符，最多4000个UTF-16单元"),"citations",citations));
    return Map.of("type","object","additionalProperties",false,"required",List.of("paragraphs"),
        "properties",Map.of("paragraphs",Map.of("type","array","minItems",1,"maxItems",20,"items",paragraph)));
  }
  public static ResponseFormat format(){return format(List.of());}
  public static ResponseFormat format(java.util.Collection<String> references){return ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
      .jsonSchema(ResponseFormat.JsonSchema.builder().name("workhub_readonly_answer").schema(schema(references)).strict(true).build()).build();}
  public static Answer parse(com.fasterxml.jackson.databind.ObjectMapper mapper,String text,Map<String,Citation> sources,Map<String,String> references) {
    try {
      if(text==null)throw new IllegalArgumentException();
      var n=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(text);
      if(n==null||!n.isObject()||n.size()!=1||!n.path("paragraphs").isArray()||n.path("paragraphs").isEmpty()||n.path("paragraphs").size()>20)throw new IllegalArgumentException("需要唯一字段paragraphs，含1至20个段落");
      var claims=new ArrayList<Claim>();
      for(var item:n.path("paragraphs")) {
        if(!item.isObject()||item.size()!=2||!item.path("text").isTextual()||!item.path("citations").isArray()||item.path("citations").size()>12)throw new IllegalArgumentException("每段仅含text字符串和citations数组");
        String content=item.path("text").asText();
        if(content.isBlank()||content.length()>4000)throw new IllegalArgumentException("段落为空或超过4000字符");
        if(Set.of("占位","占位文字","占位回答","placeholder","tbd","...").contains(content.strip().toLowerCase(Locale.ROOT)))
          throw new IllegalArgumentException("不能提交占位文字，请读取项目资料并完成回答");
        var ids=new LinkedHashSet<String>();for(var id:item.path("citations")) {
          if(!id.isTextual()||ids.size()>=12)throw new IllegalArgumentException("引用须为最多12个来源编号");
          String selected=references==null?id.asText():references.get(id.asText());
          if(selected==null||!sources.containsKey(selected))throw new IllegalArgumentException("未知或失效引用编号: "+id.asText());
          ids.add(selected);
        }
        // ANSWER is a storage/UI marker, never a claim of semantic verification.
        claims.add(new Claim("ANSWER",content,List.copyOf(ids)));
      }
      return new Answer(List.copyOf(claims),List.of());
    }catch(Exception e){String detail=e instanceof com.fasterxml.jackson.core.JsonProcessingException j?"JSON语法错误，位置 "+j.getLocation().getCharOffset():Objects.toString(e.getMessage(),"字段形状、类型、数量或引用不符合合同");throw new ApiException(503,"INVALID_AGENT_ANSWER",detail); }
  }
}
