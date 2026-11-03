package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentFactComparisonTest {
  final ObjectMapper mapper=new ObjectMapper();

  @Test void nestedSqlCountsSurviveJsonRoundTripWithoutMutatingEitherTree()throws Exception {
    var original=mapper.valueToTree(Map.of("statusCounts",List.of(Map.of("status","TODO","count",3L))));
    var restored=mapper.readTree(mapper.writeValueAsString(original));
    assertThat(original.equals(restored)).isFalse();
    assertThat(AgentTools.sameFactValue(original,restored)).isTrue();
    assertThat(AgentTools.sameFactValue(restored,original)).isTrue();
    assertThat(original.at("/statusCounts/0/count").isLong()).isTrue();
    assertThat(restored.at("/statusCounts/0/count").isInt()).isTrue();
  }

  @Test void integerRepresentationsCompareByExactValue() {
    for(int n:List.of(Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE)) {
      var nodes=List.of(IntNode.valueOf(n),LongNode.valueOf(n),BigIntegerNode.valueOf(BigInteger.valueOf(n)));
      for(var left:nodes)for(var right:nodes)assertThat(AgentTools.sameFactValue(left,right)).isTrue();
    }
    for(long n:List.of(Long.MIN_VALUE,Long.MAX_VALUE))
      assertThat(AgentTools.sameFactValue(LongNode.valueOf(n),BigIntegerNode.valueOf(BigInteger.valueOf(n)))).isTrue();
  }

  @Test void countOrStatusChangesRemainDifferent()throws Exception {
    var original=mapper.valueToTree(Map.of("statusCounts",List.of(Map.of("status","TODO","count",3L))));
    assertThat(AgentTools.sameFactValue(original,mapper.readTree("{\"statusCounts\":[{\"status\":\"TODO\",\"count\":4}]}"))).isFalse();
    assertThat(AgentTools.sameFactValue(original,mapper.readTree("{\"statusCounts\":[{\"status\":\"DONE\",\"count\":3}]}"))).isFalse();
  }

  @Test void largeIntegersDoNotLosePrecisionOrOverflow() {
    // Adjacent values collapse if compared as doubles; a long conversion can wrap large JSON integers.
    assertThat(AgentTools.sameFactValue(LongNode.valueOf(9007199254740992L),LongNode.valueOf(9007199254740993L))).isFalse();
    assertThat(AgentTools.sameFactValue(LongNode.valueOf(Long.MIN_VALUE),BigIntegerNode.valueOf(BigInteger.ONE.shiftLeft(63)))).isFalse();
  }

  @Test void otherTypesRemainStrict()throws Exception {
    for(String value:List.of("\"3\"","3.0","true","null","{}","[]")) {
      var other=mapper.readTree(value);
      assertThat(AgentTools.sameFactValue(LongNode.valueOf(3),other)).isFalse();
      assertThat(AgentTools.sameFactValue(other,LongNode.valueOf(3))).isFalse();
    }
  }

  @Test void structureAndArrayOrderRemainSignificant()throws Exception {
    var original=mapper.readTree("{\"items\":[1,2],\"status\":\"TODO\"}");
    for(String changed:List.of("{\"items\":[2,1],\"status\":\"TODO\"}","{\"items\":[1,2]}","{\"items\":[1,2],\"status\":\"TODO\",\"extra\":null}"))
      assertThat(AgentTools.sameFactValue(original,mapper.readTree(changed))).isFalse();
    assertThat(AgentTools.sameFactValue(original,mapper.readTree("{\"status\":\"TODO\",\"items\":[1,2]}"))).isTrue();
  }
}
