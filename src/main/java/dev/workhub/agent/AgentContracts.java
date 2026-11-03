package dev.workhub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.workhub.knowledge.KnowledgeReads;
import java.time.Instant;
import java.util.*;

public final class AgentContracts {
  private AgentContracts() {}
  public record Citation(String id,String kind,String title,String url,Instant observedAt,
      long businessRevision,JsonNode data,KnowledgeReads.Evidence evidence,int configId,
      String toolName,JsonNode arguments) {}
  public record ToolResult(JsonNode data,List<Citation> citations,List<String> limitations) {}
  public record Claim(String kind,String text,List<String> citations) {}
  public record Suggestion(dev.workhub.work.TaskType type, Integer priority) {}
  public record RelatedTask(UUID taskId,String relationship,String reason,List<String> citations) {}
  public record Investigation(String recommendation,List<RelatedTask> relatedTasks,List<String> questions) {}
  public record Answer(List<Claim> claims,List<String> limitations,Suggestion suggestion,Investigation investigation) {
    public Answer(List<Claim> claims,List<String> limitations){this(claims,limitations,null,null);}
    public Answer(List<Claim> claims,List<String> limitations,Suggestion suggestion){this(claims,limitations,suggestion,null);}
  }
  public record Outcome(String status,Answer answer,List<Citation> sources,List<String> tools,
      int rounds,long inputTokens,long outputTokens,long elapsedMillis,String errorCode) {}
}
