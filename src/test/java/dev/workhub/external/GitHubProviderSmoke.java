package dev.workhub.external;

import com.fasterxml.jackson.databind.*;
import dev.workhub.contract.ContractSupport;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live contracts only. Writes require a dedicated user-owned repository and explicit opt-in. */
class GitHubProviderSmoke {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json=new ObjectMapper();
    private String repo;
    @BeforeEach void credentials() {
        assumeTrue(!ContractSupport.env("GITHUB_TOKEN","").isBlank(),"BLOCKED_BY_CREDENTIAL: GITHUB_TOKEN");
        repo=ContractSupport.env("GITHUB_TEST_REPOSITORY","");
        assumeTrue(repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"),"BLOCKED_BY_TEST_REPOSITORY");
    }
    private JsonNode request(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("https://api.github.com"+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+ContractSupport.required("GITHUB_TOKEN"))
                .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28");
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GitHub %s contract HTTP status; response body omitted",method).isBetween(200,299);
        return json.readTree(response.body());
    }
    private String number(String variable) {
        String value=ContractSupport.env(variable,"");assumeTrue(value.matches("[1-9][0-9]*"),"BLOCKED_BY_FIXTURE_ID: "+variable);return value;
    }
    @Test void readIssueAndCommentSchema() throws Exception {
        String n=number("GITHUB_TEST_ISSUE_NUMBER");
        var issue=request("GET","/repos/"+repo+"/issues/"+n,null);
        assertThat(issue.path("id").asLong()).isPositive();assertThat(issue.has("state_reason")).isTrue();
        assertThat(issue.has("pull_request")).isFalse();
        var comments=request("GET","/repos/"+repo+"/issues/"+n+"/comments?per_page=1",null);
        assumeTrue(!comments.isEmpty(),"BLOCKED_BY_FIXTURE: test issue needs one comment");
        assertThat(comments.get(0).path("id").asLong()).isPositive();assertThat(comments.get(0).has("body")).isTrue();
        assertThat(comments.get(0).has("updated_at")).isTrue();
    }
    @Test void prIsDistinguishableFromIssue() throws Exception {
        var pr=request("GET","/repos/"+repo+"/issues/"+number("GITHUB_TEST_PR_NUMBER"),null);
        assertThat(pr.has("pull_request")).isTrue();
    }
    @Test void workflowRunIdentityIncludesAttemptAndSha() throws Exception {
        var run=request("GET","/repos/"+repo+"/actions/runs/"+number("GITHUB_TEST_RUN_ID"),null);
        assertThat(run.path("run_attempt").asInt()).isPositive();assertThat(run.path("head_sha").asText()).matches("[0-9a-f]{40}");
        assertThat(run.path("id").asLong()).isPositive();assertThat(run.has("conclusion")).isTrue();
    }
    @Test void duplicateReasonReadContract() throws Exception {
        var issue=request("GET","/repos/"+repo+"/issues/"+number("GITHUB_DUPLICATE_ISSUE_NUMBER"),null);
        assertThat(issue.path("state_reason").asText()).isEqualTo("duplicate");
    }
    @Test void dedicatedIssueCloseReopenAndReadBack() throws Exception {
        assumeTrue(ContractSupport.env("ALLOW_GITHUB_WRITE_SMOKE","false").equals("true"),"BLOCKED_BY_WRITE_OPT_IN");
        // A token with push rights to somebody else's public project is not enough.
        var user=request("GET","/user",null);var repository=request("GET","/repos/"+repo,null);
        assertThat(repository.at("/owner/login").asText()).isEqualToIgnoringCase(user.path("login").asText());
        assertThat(repository.at("/permissions/push").asBoolean()).isTrue();
        var created=request("POST","/repos/"+repo+"/issues",Map.of("title","[workhub-phase0] API contract fixture","body","Dedicated automated contract fixture; this issue will be left closed."));
        String path="/repos/"+repo+"/issues/"+created.path("number").asInt();
        try {
            assertThat(created.path("state").asText()).isEqualTo("open");
            request("PATCH",path,Map.of("state","closed","state_reason","completed"));
            assertThat(request("GET",path,null).path("state_reason").asText()).isEqualTo("completed");
            request("PATCH",path,Map.of("state","closed","state_reason","not_planned"));
            System.out.println("LIVE_GITHUB closed-to-closed observed reason="+request("GET",path,null).path("state_reason").asText());
            request("PATCH",path,Map.of("state","open"));
            assertThat(request("GET",path,null).path("state").asText()).isEqualTo("open");
            request("PATCH",path,Map.of("state","closed","state_reason","not_planned"));
            assertThat(request("GET",path,null).path("state_reason").asText()).isEqualTo("not_planned");
        } finally {request("PATCH",path,Map.of("state","closed"));}
    }
}
