package dev.workhub.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GitHubShapeTest {
    @Test void fixturesPreserveRawReasonsAndResourceIdentity() throws Exception {
        try(var stream=getClass().getResourceAsStream("/fixtures/github-shapes.json")) {
            var root=new ObjectMapper().readTree(stream);
            assertThat(root.path("provenance").asText()).startsWith("Synthetic");
            var issues=root.path("issues");
            assertThat(issues.get(0).path("state_reason").isNull()).isTrue();
            assertThat(issues.get(3).path("state_reason").asText()).isEqualTo("duplicate");
            assertThat(issues.get(0).has("pull_request")).isFalse();
            assertThat(issues.get(5).has("pull_request")).isTrue();
            assertThat(root.at("/comment/id").asLong()).isPositive();
            assertThat(root.at("/workflow_run/run_attempt").asInt()).isEqualTo(2);
            assertThat(root.at("/workflow_run/head_sha").asText()).matches("[0-9a-f]{40}");
        }
    }
}
