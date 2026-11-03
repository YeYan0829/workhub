package dev.workhub.agent;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
class ModelHttpFailureTest {
  @Test void retryAfterHonorsLongHintsDatesAndMalformedValues(){
    var now=Instant.parse("2026-10-16T00:00:00Z");
    assertThat(ModelHttpFailure.retryAfter(null,now)).isEqualTo(30000);
    assertThat(ModelHttpFailure.retryAfter("120",now)).isEqualTo(120000);
    assertThat(ModelHttpFailure.retryAfter("Fri, 16 Oct 2026 00:02:00 GMT",now)).isEqualTo(120000);
    for(String value:new String[]{"-1","wat","","9999999999999999999999999999"})assertThat(ModelHttpFailure.retryAfter(value,now)).isEqualTo(-1);
    assertThat(new ModelHttpFailure(429,"1",now).retryable()).isTrue();
    assertThat(new ModelHttpFailure(429,"wat",now).retryable()).isFalse();
    assertThat(new ModelHttpFailure(503,null,now).retryable()).isTrue();
  }
}
