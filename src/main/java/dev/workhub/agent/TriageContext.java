package dev.workhub.agent;

import static dev.workhub.agent.AgentContracts.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.BiFunction;

/** Deterministic RAG baseline: same recipe for every task, never sees evaluator assertions. */
final class TriageContext {
  static void collect(UUID task,ObjectMapper mapper,BiFunction<String,JsonNode,ToolResult> read) {
    var target=read.apply("get_task",mapper.valueToTree(Map.of("taskId",task)));
    read.apply("list_comments",mapper.valueToTree(Map.of("taskId",task,"limit",10)));
    var linked=read.apply("get_linked_resources",mapper.valueToTree(Map.of("taskId",task,"limit",10)));
    var opened=new HashSet<String>();
    openPaths(linked.data(),read,opened);
    // Project policy discovery uses generic terms, not scenario identifiers or answer facts.
    var policies=read.apply("find_documents",mapper.valueToTree(Map.of("query","分诊 优先级","limit",3)));
    openPaths(policies.data(),read,opened);
    String title=target.data().path("title").asText();
    if(title.isBlank())throw new IllegalArgumentException("Target title unavailable");
    var hits=read.apply("search_project_evidence",mapper.valueToTree(Map.of("query",title,"strategy","BM25","limit",8)));
    for(var c:hits.citations())if(c.evidence()!=null) {
      var e=c.evidence();String key=e.kind()+":"+e.ownerId();
      if(opened.add(key))read.apply("read_content",mapper.valueToTree(Map.of("objectType",e.kind(),"objectId",e.ownerId(),"sourceRevision",e.sourceRevision(),"start",0,"length",4000)));
    }
  }
  private static void openPaths(JsonNode n,BiFunction<String,JsonNode,ToolResult> read,Set<String> opened) {
    if(n.isObject()&&n.path("readPath").isObject()) {
      var p=n.path("readPath");String tool=p.path("tool").asText();var args=p.path("arguments");
      if(Set.of("read_content","read_github_resource").contains(tool)&&opened.size()<16&&opened.add(args.toString()))read.apply(tool,args);
    }
    n.elements().forEachRemaining(child->openPaths(child,read,opened));
  }
}
