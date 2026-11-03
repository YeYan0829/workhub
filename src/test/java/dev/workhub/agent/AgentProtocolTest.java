package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import static dev.workhub.agent.AgentContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

class AgentProtocolTest {
  final ObjectMapper mapper=new ObjectMapper();
  @Test void readableAliasesPreservePeopleAndResolveOnlyMatchingObjects()throws Exception {
    var refs=new AgentObjectRefs(mapper);String task=UUID.randomUUID().toString(),person=UUID.randomUUID().toString();
    var view=refs.view(mapper.readTree("{\"entityType\":\"TASK\",\"id\":\""+task+"\",\"assigneeId\":\""+person+"\",\"authorId\":\""+person+"\"}"));
    assertThat(view.path("id").asText()).isEqualTo("T1");assertThat(view.path("assigneeId").asText()).isEqualTo(person);assertThat(view.path("authorId").asText()).isEqualTo(person);
    assertThat(mapper.readTree(refs.resolve("{\"taskId\":\"T1\"}")).path("taskId").asText()).isEqualTo(task);
    assertThatThrownBy(()->refs.resolve("{\"objectType\":\"DOCUMENT\",\"objectId\":\"T1\"}")).isInstanceOf(ApiException.class);
  }
  @Test void resolvingHandlesDoesNotSilentlyRepairInvalidArguments() {
    var refs=new AgentObjectRefs(mapper);
    for(String raw:List.of("{\"query\":\"one\",\"query\":\"two\"}","{} {}"))
      assertThatThrownBy(()->refs.resolve(raw)).isInstanceOf(ApiException.class);
  }
  @Test void answerParserRejectsAmbiguousJsonAndUnknownReferences() {
    for(String raw:List.of("{\"paragraphs\":[]}","{\"paragraphs\":[{\"text\":\"x\",\"citations\":[\"S999\"]}]}","{\"paragraphs\":[],\"paragraphs\":[{\"text\":\"x\",\"citations\":[]}]}","{\"paragraphs\":[{\"text\":\"x\",\"citations\":[]}]} {}"))
      assertThatThrownBy(()->AgentAnswerSchema.parse(mapper,raw,Map.of(),Map.of())).isInstanceOf(ApiException.class);
    assertThat(AgentAnswerSchema.parse(mapper,"{\"paragraphs\":[{\"text\":\"目前资料不足\",\"citations\":[]}]}",Map.of(),Map.of()).claims()).hasSize(1);
  }
  @Test void placeholderIsNotACompletedAnswerButDiscussionOfPlaceholdersIsAllowed()throws Exception {
    for(String text:List.of("占位"," placeholder ","TBD","..."))
      assertThatThrownBy(()->AgentAnswerSchema.parse(mapper,mapper.writeValueAsString(Map.of("paragraphs",List.of(Map.of("text",text,"citations",List.of())))),Map.of(),Map.of())).isInstanceOf(ApiException.class);
    assertThat(AgentAnswerSchema.parse(mapper,"{\"paragraphs\":[{\"text\":\"文档讨论了占位符的处理方式\",\"citations\":[]}]}",Map.of(),Map.of()).claims()).hasSize(1);
  }
  @Test void invalidationPreservesHealthySourcesAndCanReopenSameIdentity()throws Exception {
    var c=new AgentConversation(mapper,"instructions","question");var one=source("one");var two=source("two");
    c.toolResponses(List.of(new ToolResponseMessage.ToolResponse("call","get_task",c.observe(new ToolResult(mapper.createObjectNode().put("status","TODO"),List.of(one),List.of())))));
    c.toolResponses(List.of(new ToolResponseMessage.ToolResponse("call2","list_tasks",c.observe(new ToolResult(mapper.createObjectNode().put("title","healthy"),List.of(two),List.of())))));
    c.invalidate(List.of("one"));assertThatThrownBy(()->c.answer("{\"paragraphs\":[{\"text\":\"old\",\"citations\":[\"S1\"]}]}")).isInstanceOf(ApiException.class);
    assertThat(c.selected(c.answer("{\"paragraphs\":[{\"text\":\"healthy\",\"citations\":[\"S2\"]}]}"))).containsExactly(two);
    c.observe(new ToolResult(mapper.createObjectNode(),List.of(one),List.of()));assertThat(c.selected(c.answer("{\"paragraphs\":[{\"text\":\"reopened\",\"citations\":[\"S1\"]}]}"))).containsExactly(one);
    assertThat(c.request(1000,2).toString()).contains("healthy");
  }
  @Test void queuedContractSurvivesJsonStorage()throws Exception {
    var settings=new AgentSettings(true,false,"http://127.0.0.1:12345/chat","fixture","glm-4.7","local",java.math.BigDecimal.ZERO,600,2200);
    assertThat(mapper.readValue(mapper.writeValueAsString(settings.contract()),Map.class)).isEqualTo(settings.contract());
  }
  @Test void glm53UsesUsdLedgerAndRequiredReasoningWithoutChangingOldContracts()throws Exception {
    var c=new AgentSettings(true,true,AgentProvider.GLM53.endpoint,"never-sent","glm-5.3","trial",new java.math.BigDecimal("4"),600,2200,java.math.BigDecimal.ZERO);
    assertThat(c.budget).isEqualTo(4_000_000_000L);assertThat(c.inputPrice).isEqualTo(1400);assertThat(c.outputPrice).isEqualTo(4400);
    assertThat(c.budgetTable()).isEqualTo("agent_budget");assertThat(c.callTable()).isEqualTo("agent_model_call");assertThat(c.comparison()).isFalse();
    assertThat(c.contract()).containsEntry("thinking","enabled").containsEntry("reasoningEffort","low");
    assertThat(mapper.readValue(mapper.writeValueAsString(c.contract()),Map.class)).isEqualTo(c.contract());
    assertThat(AgentProvider.GLM.extras()).isEqualTo(Map.of("thinking",Map.of("type","disabled")));
  }
  Citation source(String id){return new Citation(id,"FACT",id,"/project",java.time.Instant.now(),1,mapper.createObjectNode(),null,0,"get_task",mapper.createObjectNode());}
}
