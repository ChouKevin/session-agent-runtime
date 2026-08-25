package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SemanticRepositoryClientTest {

    @Test
    void lists_exact_published_repository_revision_pairs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
                [{"repositoryId":{"value":"payment-service"},"revision":{"value":"1111111111111111111111111111111111111111"},"generationId":{"value":"generation-a"},"manifestDigest":{"value":"digest-a"},"publishedAt":"2026-08-25T00:00:00Z"}]
                """, MediaType.APPLICATION_JSON));

        List<RepositorySummary> repositories = new SemanticRepositoryClient(builder.build()).listRepositories();

        assertThat(repositories).containsExactly(new RepositorySummary(new RepositoryId("payment-service"),
                new RepositoryRevision("1111111111111111111111111111111111111111")));
        server.verify();
    }

    @Test
    void rejects_unknown_duplicate_or_incomplete_catalog_payloads() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repositoryId":{"value":"payment-service"},"revision":{"value":"1111111111111111111111111111111111111111"},"generationId":{"value":"generation-a"},"manifestDigest":{"value":"digest-a"},"publishedAt":"2026-08-25T00:00:00Z","unexpected":true}]
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repositoryId":{"value":"payment-service"},"repositoryId":{"value":"other-service"},"revision":{"value":"1111111111111111111111111111111111111111"},"generationId":{"value":"generation-a"},"manifestDigest":{"value":"digest-a"},"publishedAt":"2026-08-25T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repositoryId":null,"revision":{"value":"1111111111111111111111111111111111111111"},"generationId":{"value":"generation-a"},"manifestDigest":{"value":"digest-a"},"publishedAt":"2026-08-25T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(client.semantic()::listRepositories);
        assertInvalidResponse(client.semantic()::listRepositories);
        assertInvalidResponse(client.semantic()::listRepositories);
        client.server().verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void maps_catalog_access_denial_without_leaking_provider_text(int status) {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("provider secret"));

        assertThatThrownBy(client.semantic()::listRepositories)
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.FORBIDDEN);
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });
        client.server().verify();
    }

    @Test
    void maps_network_and_bounded_retry_after_to_unavailable() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withException(new java.net.SocketTimeoutException("network secret")));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "90"));

        assertThatThrownBy(client.semantic()::listRepositories)
                .isInstanceOfSatisfying(SemanticFailure.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(SemanticFailure.Kind.SEMANTIC_INDEX_UNAVAILABLE));
        assertThatThrownBy(client.semantic()::listRepositories)
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.SEMANTIC_INDEX_UNAVAILABLE);
                    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(60));
                });
        client.server().verify();
    }

    private static void assertInvalidResponse(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(SemanticFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.INVALID_RESPONSE));
    }

    private static TestClient testClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new SemanticRepositoryClient(builder.build()), server);
    }

    private record TestClient(SemanticRepositoryClient semantic, MockRestServiceServer server) {
    }
}
