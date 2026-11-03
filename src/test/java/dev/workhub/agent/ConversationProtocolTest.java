package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

class ConversationProtocolTest {
  @Test void checkpointRoundTripPreservesObjectsAndCompleteToolBatches()throws Exception{
    var mapper=new ObjectMapper().findAndRegisterModules();var c=new AgentConversation(mapper,"server instructions","current question");
    String task=UUID.randomUUID().toString();c.observe(new AgentContracts.ToolResult(mapper.readTree("{\"objectType\":\"TASK\",\"objectId\":\""+task+"\"}"),List.of(),List.of()));
    for(int i=0;i<3;i++){c.assistant(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call"+i,"function","get_task","{\"taskId\":\"T1\"}"))).build());c.toolResponses(List.of(new ToolResponseMessage.ToolResponse("call"+i,"get_task","{\"data\":{}}")));}
    assertThat(c.oldBatches()).hasSize(4);c.replaceOldBatches("bounded earlier observations");
    var snapshot=mapper.readValue(mapper.writeValueAsString(c.snapshot()),AgentConversation.Snapshot.class);var recovered=new AgentConversation(mapper,"unused","unused");recovered.restore(snapshot);
    assertThat(recovered.arguments("{\"taskId\":\"T1\"}")).contains(task);assertThat(recovered.snapshot().messages()).hasSize(5);
    assertThat(recovered.snapshot().messages().get(3).calls()).hasSize(1);assertThat(recovered.snapshot().messages().get(4).responses()).hasSize(1);
    assertThat(recovered.snapshot().messages().get(1).text()).isEqualTo("current question");
  }
  @Test void orphanedToolResultCannotBeRestored(){
    var mapper=new ObjectMapper();var c=new AgentConversation(mapper,"system","question");
    var bad=new AgentConversation.Snapshot(List.of(new AgentConversation.Protocol("system","system",List.of(),List.of()),new AgentConversation.Protocol("tool","",List.of(),List.of(new ToolResponseMessage.ToolResponse("missing","get_task","{}")))),List.of(),Map.of(),Map.of(),Map.of());
    assertThatThrownBy(()->c.restore(bad)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Orphan");
  }
}
