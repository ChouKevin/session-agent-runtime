package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.input.ListEntryPointsInput;
import com.java.system.sessionagent.semantic.tool.input.LookupApiRouteInput;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class FakeSemanticServiceTest {

    @Test
    void rejects_api_route_evidence_outside_the_payment_service_scope() {
        try (FakeSemanticService semantic = new FakeSemanticService()) {
            RestClient restClient = RestClient.builder().baseUrl(semantic.baseUrl()).build();
            SemanticSourceClient source = new SemanticSourceClient(restClient);

            assertThatThrownBy(() -> source.lookupApiRoute(new LookupApiRouteInput("order-service", "order-revision-1", "GET", "/bnpl")))
                    .isInstanceOfSatisfying(SemanticFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.REPOSITORY_NOT_FOUND));
            assertThat(semantic.calls()).filteredOn(call -> call.path().equals("/v1/api-routes/lookup"))
                    .singleElement().satisfies(call -> assertThat(call.body())
                            .contains("\"repositoryId\":\"order-service\"", "\"revision\":\"order-revision-1\""));
        }
    }

    @Test
    void rejects_entry_points_without_the_repository_current_expected_revision() {
        try (FakeSemanticService semantic = new FakeSemanticService()) {
            RestClient restClient = RestClient.builder().baseUrl(semantic.baseUrl()).build();

            assertAll(
                    () -> assertThatThrownBy(() -> restClient.get().uri("/v1/repositories/payment-service/entry-points")
                            .retrieve().toBodilessEntity()).isInstanceOfSatisfying(RestClientResponseException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409)),
                    () -> assertThatThrownBy(() -> restClient.get().uri("/v1/repositories/payment-service/entry-points?expectedRevision=stale-revision")
                            .retrieve().toBodilessEntity()).isInstanceOfSatisfying(RestClientResponseException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409)));
        }
    }

    @Test
    void returns_revision_refresh_facts_only_inside_the_semantic_failure_observation_contract() {
        try (FakeSemanticService semantic = new FakeSemanticService()) {
            RestClient restClient = RestClient.builder().baseUrl(semantic.baseUrl()).build();
            SemanticSourceClient source = new SemanticSourceClient(restClient);

            assertThatThrownBy(() -> source.listEntryPoints(new ListEntryPointsInput("payment-service", "stale-revision")))
                    .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.REVISION_OUTDATED);
                        assertThat(failure.revisionOutdated().orElseThrow().currentRevision()).isEqualTo("payment-revision-1");
                    });
        }
    }
}
