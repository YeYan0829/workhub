package dev.workhub.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.workhub.common.ApiException;
import java.util.*;

/** Run-local navigation aliases. Canonical source identities stay in existing citations. */
public final class AgentObjectRefs {
  private record ObjectId(String type,String id) {}
  private final Map<String,ObjectId> objects=new LinkedHashMap<>();
  private final Map<ObjectId,String> aliases=new HashMap<>();
  private final ObjectMapper mapper;
  public AgentObjectRefs(ObjectMapper mapper){this.mapper=mapper;}
  private String bind(String type,String id) {
    if(!Set.of("TASK","DOCUMENT","COMMENT","GITHUB_COMMENT").contains(type))return id;
    try{UUID.fromString(id);}catch(IllegalArgumentException e){return id;}
    var object=new ObjectId(type,id);
    return aliases.computeIfAbsent(object,key->{String prefix=switch(type){case "TASK"->"T";case "DOCUMENT"->"D";case "COMMENT"->"C";default->"G";};
      String ref=prefix+(objects.size()+1);objects.put(ref,key);return ref;});
  }
  public JsonNode view(JsonNode input){var copy=input.deepCopy();visit(copy,"");return copy;}
  private void visit(JsonNode node,String context) {
    if(node.isObject()) {
      var o=(ObjectNode)node;
      String type=o.path("objectType").asText(o.path("entityType").asText(context));
      if(o.path("id").isTextual()&&type.equals("TASK"))o.put("id",bind(type,o.path("id").asText()));
      for(String field:List.of("taskId","fromTaskId","toTaskId"))if(o.path(field).isTextual())o.put(field,bind("TASK",o.path(field).asText()));
      if(o.path("objectId").isTextual())o.put("objectId",bind(type,o.path("objectId").asText()));
      if(o.path("path").isArray()){var path=(com.fasterxml.jackson.databind.node.ArrayNode)o.path("path");for(int i=0;i<path.size();i++)if(path.get(i).isTextual())path.set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(bind("TASK",path.get(i).asText())));}
      o.fields().forEachRemaining(f->visit(f.getValue(),Set.of("nodes","blockers").contains(f.getKey())?"TASK":""));
    } else if(node.isArray())node.forEach(n->visit(n,context));
  }
  public String resolve(String raw) {
    try {
      var n=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);if(n==null||!n.isObject())throw new IllegalArgumentException();var o=(ObjectNode)n;
      for(String field:List.of("taskId","fromTaskId","toTaskId","objectId"))if(o.has(field)) {
        String value=o.path(field).asText();var object=objects.get(value);
        if(object!=null){String expected=field.equals("objectId")?o.path("objectType").asText():"TASK";
          if(!expected.equals(object.type()))throw new IllegalArgumentException();o.put(field,object.id());}
      }
      return mapper.writeValueAsString(o);
    }catch(Exception e){throw new ApiException(422,"INVALID_TOOL_CALL","对象引用与参数类型不匹配；请使用工具返回的对应TASK/DOCUMENT/COMMENT引用");}
  }
  public void restore(Map<String,Map<String,String>> values) {
    objects.clear();aliases.clear();
    values.forEach((alias,value)->{
      String type=value.get("type"),id=value.get("id");UUID.fromString(id);
      if(!alias.matches("[TDCG][1-9][0-9]*") || !Set.of("TASK","DOCUMENT","COMMENT","GITHUB_COMMENT").contains(type))throw new IllegalArgumentException("Invalid object registry");
      var identity=new ObjectId(type,id);objects.put(alias,identity);aliases.put(identity,alias);
    });
  }
  public Map<String,Map<String,String>> bindings(){var result=new LinkedHashMap<String,Map<String,String>>();objects.forEach((k,v)->result.put(k,Map.of("type",v.type(),"id",v.id())));return result;}
}
