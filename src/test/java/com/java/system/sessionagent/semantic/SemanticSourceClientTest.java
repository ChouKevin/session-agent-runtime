package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.input.SearchCodeFactsInput;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SemanticSourceClientTest {

    @Test
    void forwards_exact_code_fact_search_identifiers_and_omits_absent_filters() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/code-facts/search"))
                .andExpect(content().json("""
                        {"repositoryId":"payment-service","revision":"1111111111111111111111111111111111111111","query":"refund"}
                        """))
                .andRespond(withSuccess("""
                        {"repositoryId":"payment-service","revision":"1111111111111111111111111111111111111111","result":[]}
                        """, MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<JsonNode> result = new SemanticSourceClient(builder.build())
                .searchCodeFacts(new SearchCodeFactsInput("payment-service",
                        "1111111111111111111111111111111111111111", "refund",
                        null, // cs-allow omitted optional filter
                        null, // cs-allow omitted optional filter
                        null, // cs-allow omitted optional filter
                        null)); // cs-allow omitted optional filter

        assertThat(result.revision().value()).isEqualTo("1111111111111111111111111111111111111111");
        server.verify();
    }

    @Test
    void preserves_valid_revision_outdated_details_without_a_runtime_retry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("""
                        {"code":"REVISION_OUTDATED","repositoryId":"payment-service","requestedRevision":"1111111111111111111111111111111111111111","currentRevision":"2222222222222222222222222222222222222222","retryGuidance":"Retry with currentRevision."}
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new SemanticSourceClient(builder.build()).searchCodeFacts(omittedFilters()))
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.REVISION_OUTDATED);
                    assertThat(failure.revisionOutdated().orElseThrow().currentRevision())
                            .isEqualTo("2222222222222222222222222222222222222222");
                });
        server.verify();
    }

    @Test
    void rejects_scope_mismatched_revision_outdated_payload_as_invalid_response() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("""
                        {"code":"REVISION_OUTDATED","repositoryId":"other-service","requestedRevision":"1111111111111111111111111111111111111111","currentRevision":"2222222222222222222222222222222222222222","retryGuidance":"Retry with currentRevision."}
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new SemanticSourceClient(builder.build()).searchCodeFacts(omittedFilters()))
                .isInstanceOfSatisfying(SemanticFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.INVALID_RESPONSE));
        server.verify();
    }

    private static SearchCodeFactsInput omittedFilters() {
        return new SearchCodeFactsInput("payment-service", "1111111111111111111111111111111111111111", "refund",
                null, // cs-allow omitted optional filter
                null, // cs-allow omitted optional filter
                null, // cs-allow omitted optional filter
                null); // cs-allow omitted optional filter
    }
}
