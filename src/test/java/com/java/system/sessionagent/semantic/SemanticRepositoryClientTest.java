package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class SemanticRepositoryClientTest {

    @Test
    void lists_repositories_with_their_exact_ids_and_display_names() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"currentRevision":"abc","displayName":"Payment Service","cloned":true,"repoId":"payment-service","mode":"REMOTE","currentBranch":"main"}]
                        """, MediaType.APPLICATION_JSON));

        List<RepositorySummary> repositories = client.semantic().listRepositories();

        assertEquals(List.of(new RepositorySummary(new RepositoryId("payment-service"), "Payment Service")), repositories);
        client.server().verify();
    }

    @Test
    void checks_the_exact_requested_repository_against_live_status_without_catalog_membership() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"repoId":"listed","mode":"REMOTE","displayName":"Listed","currentBranch":"main","currentRevision":"abc","cloned":true}]
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));

        client.semantic().listRepositories();
        RepositoryRevision revision = client.semantic().currentRevision(new RepositoryId("payment-service"));

        assertEquals(new RepositoryRevision("revision-42"), revision);
        client.server().verify();
    }

    @Test
    void checks_current_revision_live_on_every_call_without_caching() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-1","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-2","cloned":true}
                        """, MediaType.APPLICATION_JSON));

        RepositoryRevision first = client.semantic().currentRevision(new RepositoryId("payment-service"));
        RepositoryRevision second = client.semantic().currentRevision(new RepositoryId("payment-service"));

        assertEquals(new RepositoryRevision("revision-1"), first);
        assertEquals(new RepositoryRevision("revision-2"), second);
        client.server().verify();
    }

    @Test
    void rejects_a_status_response_for_a_different_repository() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"other-service","mode":"REMOTE","displayName":"Other Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("payment-service")));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
        client.server().verify();
    }

    @Test
    void rejects_unknown_status_response_properties() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true,"unexpected":true}
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("payment-service")));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
        client.server().verify();
    }

    @Test
    void maps_unknown_repository_to_a_safe_correctable_failure() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("repository missing"));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("missing")));

        assertEquals(SemanticFailure.Kind.UNKNOWN_REPOSITORY, failure.kind());
        assertEquals("Semantic repository was not found", failure.getMessage());
        assertTrue(failure.retryAfter().isEmpty());
        assertFalse(failure.getMessage().contains("missing"));
        client.server().verify();
    }

    @Test
    void treats_a_catalog_not_found_response_as_an_invalid_contract() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("catalog missing"));

        SemanticFailure failure = assertThrows(SemanticFailure.class, client.semantic()::listRepositories);

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
        assertFalse(failure.getMessage().contains("catalog"));
        client.server().verify();
    }

    @Test
    void rejects_unknown_catalog_response_properties() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true,"unexpected":true}]
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure failure = assertThrows(SemanticFailure.class, client.semantic()::listRepositories);

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
        client.server().verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void maps_access_denial_to_forbidden_without_leaking_the_response(int status) {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("secret token response"));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("payment-service")));

        assertEquals(SemanticFailure.Kind.FORBIDDEN, failure.kind());
        assertEquals("Semantic service access is forbidden", failure.getMessage());
        assertFalse(failure.getMessage().contains("payment-service"));
        assertFalse(failure.getMessage().contains("secret"));
        client.server().verify();
    }

    @Test
    void maps_timeout_and_valid_transient_retry_after_to_transient() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withException(new java.net.SocketTimeoutException("network secret")));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/rate-limited"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "42"));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/unavailable"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "invalid"));

        SemanticFailure timeout = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("payment-service")));
        SemanticFailure rateLimited = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("rate-limited")));
        SemanticFailure unavailable = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("unavailable")));

        assertEquals(SemanticFailure.Kind.TRANSIENT, timeout.kind());
        assertEquals(SemanticFailure.Kind.TRANSIENT, rateLimited.kind());
        assertEquals(Duration.ofSeconds(42), rateLimited.retryAfter().orElseThrow());
        assertEquals(SemanticFailure.Kind.TRANSIENT, unavailable.kind());
        assertTrue(unavailable.retryAfter().isEmpty());
        client.server().verify();
    }

    @Test
    void caps_valid_retry_after_delays_at_sixty_seconds() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/at-limit"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/over-limit"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "61"));

        SemanticFailure atLimit = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("at-limit")));
        SemanticFailure overLimit = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("over-limit")));

        assertEquals(Duration.ofSeconds(60), atLimit.retryAfter().orElseThrow());
        assertEquals(Duration.ofSeconds(60), overLimit.retryAfter().orElseThrow());
        client.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "-1", "Sun, 06 Nov 1994 08:49:37 GMT"})
    void omits_invalid_retry_after_values(String retryAfter) {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/unavailable"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", retryAfter));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("unavailable")));

        assertEquals(SemanticFailure.Kind.TRANSIENT, failure.kind());
        assertTrue(failure.retryAfter().isEmpty());
        client.server().verify();
    }

    @Test
    void rejects_malformed_or_not_ready_status_as_invalid_response() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/malformed"))
                .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/not-ready"))
                .andRespond(withSuccess("""
                        {"repoId":"not-ready","mode":"REMOTE","displayName":"Not ready","currentBranch":"main","currentRevision":"   ","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/no-revision"))
                .andRespond(withSuccess("""
                        {"repoId":"no-revision","mode":"REMOTE","displayName":"No revision","currentBranch":"main","currentRevision":null,"cloned":true}
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure malformed = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("malformed")));
        SemanticFailure notReady = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("not-ready")));
        SemanticFailure noRevision = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("no-revision")));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, malformed.kind());
        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, notReady.kind());
        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, noRevision.kind());
        assertEquals("Semantic service returned an invalid response", malformed.getMessage());
        client.server().verify();
    }

    @Test
    void rejects_duplicate_or_incomplete_repository_contracts() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/duplicate"))
                .andRespond(withSuccess("""
                        {"repoId":"duplicate","repoId":"other","mode":"REMOTE","displayName":"Duplicate","currentBranch":"main","currentRevision":"revision-1","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/not-cloned"))
                .andRespond(withSuccess("""
                        {"repoId":"not-cloned","mode":"REMOTE","displayName":"Not cloned","currentBranch":"main","currentRevision":"revision-1","cloned":false}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/missing-branch"))
                .andRespond(withSuccess("""
                        {"repoId":"missing-branch","mode":"REMOTE","displayName":"Missing branch","currentRevision":"revision-1","cloned":true}
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure duplicate = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("duplicate")));
        SemanticFailure notCloned = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("not-cloned")));
        SemanticFailure missingBranch = assertThrows(SemanticFailure.class,
                () -> client.semantic().currentRevision(new RepositoryId("missing-branch")));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, duplicate.kind());
        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, notCloned.kind());
        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, missingBranch.kind());
        client.server().verify();
    }

    private static TestClient testClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new SemanticRepositoryClient(builder.build()), server);
    }

    private record TestClient(SemanticRepositoryClient semantic, MockRestServiceServer server) {
    }
}
