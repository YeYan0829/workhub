package dev.workhub.agent;

import static dev.workhub.agent.AgentContracts.*;
import static dev.workhub.common.Ids.*;
import com.fasterxml.jackson.databind.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.*;
import dev.workhub.work.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Bounded navigation over authorized business objects and canonical Source text. */
@Service
public class AgentContent {
  private final ProjectAccess access; private final ContentService content; private final TaskService tasks;
  private final CollaborationService collaboration; private final KnowledgeReads reads;
  private final JdbcTemplate db; private final ObjectMapper mapper;
  private final org.springframework.transaction.support.TransactionTemplate sourceRead;
  public AgentContent(ProjectAccess access,ContentService content,TaskService tasks,CollaborationService collaboration,
      KnowledgeReads reads,JdbcTemplate db,ObjectMapper mapper,org.springframework.transaction.PlatformTransactionManager transactions){this.access=access;this.content=content;this.tasks=tasks;
    this.collaboration=collaboration;this.reads=reads;this.db=db;this.mapper=mapper;
    sourceRead=new org.springframework.transaction.support.TransactionTemplate(transactions);
    sourceRead.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    sourceRead.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);sourceRead.setReadOnly(true);sourceRead.setTimeout(5);
  }

  public static Map<String,Object> openArgs(String kind,UUID id,long revision,int start){
    return Map.of("objectType",kind,"objectId",id,"sourceRevision",revision,"start",start,"length",2000);
  }
  public Map<String,Object> metadata(ActorContext a,UUID p,String kind,UUID id){
    access.read(a,p);var m=new LinkedHashMap<String,Object>();m.put("objectType",kind);m.put("objectId",id);
    switch(kind){
      case "TASK" -> {var t=tasks.get(a,p,id);m.put("title",t.title());}
      case "DOCUMENT" -> {var d=content.getDocument(a,p,id);m.put("title",d.title());m.put("documentType",d.kind());m.put("updatedAt",d.updatedAt());m.putAll(db.queryForMap("SELECT BIN_TO_UUID(d.created_by) authorId,u.display_name authorDisplayName FROM project_document d LEFT JOIN app_user u ON u.id=d.created_by WHERE d.id=? AND d.project_id=?",bytes(id),bytes(p)));m.put("authority","用户维护的原文，类型或标题不证明陈述正确");}
      case "COMMENT" -> {var c=content.getComment(a,p,id);m.put("taskId",c.taskId());m.put("taskTitle",tasks.get(a,p,c.taskId()).title());m.put("authorId",c.authorId());m.put("createdAt",c.createdAt());
        m.put("authorDisplayName",db.queryForObject("SELECT display_name FROM app_user WHERE id=?",String.class,bytes(c.authorId())));}
      case "GITHUB_COMMENT" -> m.put("createdAtKnown",false);
      default -> throw ApiException.invalid("Unknown object type");
    }
    return m;
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
  public Citation excerpt(ActorContext a,UUID p,String kind,UUID id,Long revision,int start,int length){
    var meta=metadata(a,p,kind,id);
    var e=reads.open(a,p,kind,id,revision,start,length);
    int total=reads.contentLength(a,p,e.sourceId());
    meta.put("range",Map.of("start",e.charStart(),"end",e.charEnd(),"totalCharacters",total,"unit","UTF-16"));
    meta.put("truncated",e.charEnd()<total);meta.put("readPath",Map.of("tool","read_content","arguments",openArgs(kind,id,e.sourceRevision(),e.charStart())));
    if(e.charEnd()<total)meta.put("continue",Map.of("tool","read_content","arguments",openArgs(kind,id,e.sourceRevision(),e.charEnd())));
    return new Citation("e-direct-"+e.sourceId()+"-"+e.sourceRevision()+"-"+e.charStart()+"-"+e.charEnd(),"EVIDENCE",e.title(),AgentTools.safeUrl(e.canonicalUrl()),Instant.now(),access.read(a,p).businessRevision(),clean(meta),e,0,"read_content",mapper.valueToTree(openArgs(kind,id,e.sourceRevision(),e.charStart())));
  }
  public Map<String,Object> documentSummary(ActorContext a,UUID p,UUID id){
    var d=content.getDocument(a,p,id);var e=reads.open(a,p,"DOCUMENT",id,null,0,1);
    return Map.of("objectType","DOCUMENT","objectId",id,"title",d.title(),"documentType",d.kind(),"updatedAt",d.updatedAt(),
        "readPath",Map.of("tool","read_content","arguments",openArgs("DOCUMENT",id,e.sourceRevision(),0)));
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
  public ToolResult execute(ActorContext a,UUID p,String name,JsonNode args){
    access.read(a,p);Object value;var citations=new ArrayList<Citation>();var unavailable=new ArrayList<Map<String,Object>>();int limit=args.path("limit").asInt(5);
    switch(name){
      case "read_content" -> {var c=excerpt(a,p,args.path("objectType").asText(),UUID.fromString(args.path("objectId").asText()),args.has("sourceRevision")?args.get("sourceRevision").asLong():null,args.path("start").asInt(),args.path("length").asInt(2000));citations.add(c);value=Map.of("scope","指定对象的原文范围；原文不是业务规则");}
      case "find_documents" -> {var list=content.findDocuments(a,p,cursor(args),limit+1,args.path("query").asText(""));boolean more=list.size()>limit;var page=list.stream().limit(limit).toList();
        var items=new ArrayList<Map<String,Object>>();
        var terms=ContentService.documentQueryTerms(args.path("query").asText(""));
        for(var d:page){String body=d.title()+"\n"+d.body();int hit=-1;
          for(String term:terms){var match=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(term),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(body);if(match.find()&&(hit<0||match.start()<hit))hit=match.start();}
          int start=Math.max(0,hit-100);var c=optionalExcerpt(a,p,"DOCUMENT",d.id(),start,400,unavailable);if(c==null){items.add(unavailable.get(unavailable.size()-1));continue;}citations.add(c);
          var item=new LinkedHashMap<String,Object>();item.put("objectType","DOCUMENT");item.put("objectId",d.id());item.put("title",d.title());item.put("documentType",d.kind());item.put("updatedAt",d.updatedAt());item.put("previewKind",hit<0?"DOCUMENT_HEAD":"LITERAL_MATCH_CONTEXT");item.put("matchStart",hit);
          item.put("readPath",Map.of("tool","read_content","arguments",openArgs("DOCUMENT",d.id(),c.evidence().sourceRevision(),start)));items.add(item);
        }
        value=Map.of("items",items,"nextCursor",more?token(page.get(page.size()-1).updatedAt(),page.get(page.size()-1).id()):"","scope","当前项目标题或正文包含任一查询词的候选，不保证与所有词相关；空query列出文档；每条预览最多400字符；LITERAL_MATCH_CONTEXT围绕字面命中，DOCUMENT_HEAD为开头（空查询或数据库排序规则匹配而无精确位置）；matchStart为原文UTF-16位置，按readPath/continue读取","hasMore",more,"matchMode","ANY_LITERAL_TERM","queryTerms",ContentService.documentQueryTerms(args.path("query").asText("")));}
      case "list_comments" -> {UUID task=UUID.fromString(args.path("taskId").asText());tasks.get(a,p,task);var list=content.comments(a,p,task,cursor(args),limit+1);boolean more=list.size()>limit;var page=list.stream().limit(limit).toList();
        for(var c:page){var excerpt=optionalExcerpt(a,p,"COMMENT",c.id(),0,1000,unavailable);if(excerpt!=null)citations.add(excerpt);}
        value=Map.of("taskId",task,"order","createdAt,id ascending","returned",page.size(),"hasMore",more,"nextCursor",more?token(page.get(page.size()-1).createdAt(),page.get(page.size()-1).id()):"","scope","指定任务未删除评论；每条正文最多1000字符，更多正文用continue");}
      default -> throw ApiException.invalid("Unknown content operation");
    }
    var data=(com.fasterxml.jackson.databind.node.ObjectNode)clean(value);data.put("complete",unavailable.isEmpty());data.set("unavailable",clean(unavailable));
    if(Set.of("list_comments","find_documents").contains(name)) {
      var navigation=data.putObject("navigation");
      var reload=args.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)reload).remove("cursor");
      navigation.set("reload",mapper.valueToTree(Map.of("tool",name,"arguments",reload)));
      if(data.path("hasMore").asBoolean()) {
        var next=(com.fasterxml.jackson.databind.node.ObjectNode)args.deepCopy();next.put("cursor",data.path("nextCursor").asText());
        navigation.set("nextPage",mapper.valueToTree(Map.of("tool",name,"arguments",next)));
      }
      navigation.put("scope","查询入口，不是当前完整性证明；nextPage沿用原页游标，reload从头重查，可发现排序变化或新增资料");
    }
    return new ToolResult(data,List.copyOf(citations),unavailable.isEmpty()?List.of():List.of("本页部分正文不可用；有效项已保留，unavailable列出失败项及重新打开入口"));
  }
  private Citation optionalExcerpt(ActorContext a,UUID p,String kind,UUID id,int start,int length,List<Map<String,Object>> unavailable) {
    try{return sourceRead.execute(status->excerpt(a,p,kind,id,null,start,length));}
    catch(ApiException e){if(e.status!=404&&e.status!=409)throw e;
      unavailable.add(Map.of("objectType",kind,"objectId",id,"complete",false,"error",e.code,
        "readPath",Map.of("tool","read_content","arguments",Map.of("objectType",kind,"objectId",id,"start",0,"length",2000))));return null;}
  }
  public Object findTasks(ActorContext a,UUID p,JsonNode args){
    int offset=args.path("offset").asInt(),limit=args.path("limit").asInt(10);
    var page=collaboration.query(a,p,args.path("query").asText(""),args.path("status").asText(""),null,null,null,offset,limit);
    return Map.of("items",page.items(),"hasMore",page.hasMore(),"nextOffset",page.hasMore()?offset+page.items().size():-1,
      "scope","当前项目标题连续字面匹配整个query，不分词、不查描述；正文发现用search_project_evidence，空query浏览。同名对象请打开描述区分");
  }
  private TaskRepository.Cursor cursor(JsonNode args){
    if(!args.has("cursor"))return null;
    try{String[] parts=args.get("cursor").asText().split("~");if(parts.length!=2)throw new IllegalArgumentException();return new TaskRepository.Cursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));}
    catch(RuntimeException e){throw ApiException.invalid("Use the returned nextCursor unchanged");}
  }
  private String token(Instant at,UUID id){return at+"~"+id;}
  private JsonNode clean(Object value){try{return mapper.valueToTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
