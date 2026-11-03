package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.common.ApiException;
import org.junit.jupiter.api.Test;

class AgentTriageProtocolTest {
  AgentConversation conversation(){return new AgentConversation(new ObjectMapper(),"synthetic","fixture");}
  String answer(String suggestion){return "{\"paragraphs\":[{\"text\":\"合成资料不足\",\"citations\":[]}],\"suggestion\":"+suggestion+"}";}
  @Test void acceptsUncertaintyWithoutInventingAnOwner(){
    var a=conversation().triageAnswer(answer("{\"type\":null,\"priority\":null}"));assertThat(a.suggestion().type()).isNull();assertThat(a.suggestion().priority()).isNull();
  }
  @Test void refusesExtraActionsOwnersTypesAndInvalidPriority(){
    for(var s:new String[]{"{\"type\":\"BUG\",\"priority\":1,\"action\":\"accept\"}","{\"type\":\"BUG\",\"priority\":1,\"assigneeId\":\"invented\"}","{\"type\":\"OTHER\",\"priority\":1}","{\"type\":\"BUG\",\"priority\":1.5}","{\"type\":\"BUG\",\"priority\":4294967297}","{\"type\":\"BUG\",\"priority\":1,\"priority\":2}"})
      assertThatThrownBy(()->conversation().triageAnswer(answer(s))).isInstanceOf(ApiException.class);
  }
  @Test void readonlyAnswerRejectsTriageShape(){assertThatThrownBy(()->conversation().answer(answer("{\"type\":\"BUG\",\"priority\":1}"))).isInstanceOf(ApiException.class);}
  String investigation(String value){return answer("{\"type\":null,\"priority\":null}").replaceFirst("}$",",\"investigation\":"+value+"}");}
  @Test void investigationRequiresSpecificQuestionsAndKnownDecision(){
    for(String value:new String[]{"{\"recommendation\":\"NEED_INFO\",\"relatedTasks\":[],\"questions\":[]}","{\"recommendation\":\"WRITE_COMMENT\",\"relatedTasks\":[],\"questions\":[\"版本？\"]}","{\"recommendation\":\"POSSIBLE_DUPLICATE\",\"relatedTasks\":[],\"questions\":[]}"})
      assertThatThrownBy(()->conversation().triageAnswer(investigation(value))).isInstanceOf(ApiException.class);
  }
  @Test void validInvestigationKeepsQuestionsSeparateFromFacts(){
    var a=conversation().triageAnswer(investigation("{\"recommendation\":\"NEED_INFO\",\"relatedTasks\":[],\"questions\":[\"发生在哪个版本？\"]}"));
    assertThat(a.investigation().recommendation()).isEqualTo("NEED_INFO");assertThat(a.investigation().questions()).containsExactly("发生在哪个版本？");
  }
  @Test void relatedTaskMustBeObservedNotJustValidUuid(){
    assertThatThrownBy(()->conversation().triageAnswer(investigation("{\"recommendation\":\"POSSIBLE_DUPLICATE\",\"relatedTasks\":[{\"taskId\":\""+java.util.UUID.randomUUID()+"\",\"relationship\":\"POSSIBLE_DUPLICATE\",\"reason\":\"猜测相似\",\"citations\":[]}],\"questions\":[]}"))).isInstanceOf(ApiException.class);
  }
}
