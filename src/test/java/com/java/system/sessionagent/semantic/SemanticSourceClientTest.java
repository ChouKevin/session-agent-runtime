package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.input.CodeFactKind;
import com.java.system.sessionagent.semantic.tool.input.DiscoverEventListenersInput;
import com.java.system.sessionagent.semantic.tool.input.DiscoverMethodImplementationsInput;
import com.java.system.sessionagent.semantic.tool.input.DiscoverTypeMembersInput;
import com.java.system.sessionagent.semantic.tool.input.FindInternalReferencesInput;
import com.java.system.sessionagent.semantic.tool.input.GetCodeFactInput;
import com.java.system.sessionagent.semantic.tool.input.GetEvidenceSourceInput;
import com.java.system.sessionagent.semantic.tool.input.GetMethodSourceInput;
import com.java.system.sessionagent.semantic.tool.input.GetSourceSegmentInput;
import com.java.system.sessionagent.semantic.tool.input.IncomingCallGraphInput;
import com.java.system.sessionagent.semantic.tool.input.ListEntryPointsInput;
import com.java.system.sessionagent.semantic.tool.input.LookupApiRouteInput;
import com.java.system.sessionagent.semantic.tool.input.OutgoingCallGraphInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveSourceSymbolInput;
import com.java.system.sessionagent.semantic.tool.input.SearchCodeFactsInput;
import com.java.system.sessionagent.semantic.tool.input.SuggestApiRouteInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SemanticSourceClientTest {

    private static final String BASE_URL = "https://semantic.test";
    private static final String REPOSITORY_ID = "payment-service";
    private static final String REVISION = "1".repeat(40);
    private static final String CURRENT_REVISION = "2".repeat(40);
    private static final String FACT_ID = "f".repeat(64);

    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceToolContracts")
    void forwards_each_flat_query_contract_exactly(
            String name,
            HttpMethod httpMethod,
            String path,
            Optional<String> expectedBody,
            SourceCall call) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResponseActions expectation = server.expect(once(), requestTo(BASE_URL + path)).andExpect(method(httpMethod));
        expectedBody.ifPresent(body -> expectation.andExpect(content().json(body, JsonCompareMode.STRICT)));
        expectation.andRespond(withSuccess(successEnvelope(), MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<JsonNode> result = call.invoke(new SemanticSourceClient(builder.build()));

        assertThat(result.revision().value()).isEqualTo(REVISION);
        assertThat(result.response().path("fact").textValue()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void rejects_bare_or_scope_mismatched_success_responses() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withSuccess("{\"fact\":\"bare\"}", MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withSuccess(successEnvelope().replace(REPOSITORY_ID, "other-service"),
                        MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.semantic().searchCodeFacts(searchInput()));
        assertInvalidResponse(() -> client.semantic().searchCodeFacts(searchInput()));
        client.server().verify();
    }

    @Test
    void preserves_valid_revision_outdated_details_without_a_runtime_retry() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body(stalePayload(REPOSITORY_ID, REVISION, CURRENT_REVISION))
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.semantic().searchCodeFacts(searchInput()))
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.REVISION_OUTDATED);
                    assertThat(failure.revisionOutdated().orElseThrow().currentRevision()).isEqualTo(CURRENT_REVISION);
                });
        client.server().verify();
    }

    @Test
    void rejects_malformed_or_scope_mismatched_revision_outdated_payloads() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body(stalePayload("other-service", REVISION, CURRENT_REVISION))
                        .contentType(MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body(stalePayload(REPOSITORY_ID, REVISION, REVISION))
                        .contentType(MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"code\":\"REVISION_OUTDATED\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.semantic().searchCodeFacts(searchInput()));
        assertInvalidResponse(() -> client.semantic().searchCodeFacts(searchInput()));
        assertInvalidResponse(() -> client.semantic().searchCodeFacts(searchInput()));
        client.server().verify();
    }

    @Test
    void maps_query_auth_and_unavailable_failures_without_leaking_provider_text() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("provider secret"));
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "90")
                        .body("{\"code\":\"SEMANTIC_INDEX_UNAVAILABLE\",\"retryable\":true}"));

        assertThatThrownBy(() -> client.semantic().searchCodeFacts(searchInput()))
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.FORBIDDEN);
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });
        assertThatThrownBy(() -> client.semantic().searchCodeFacts(searchInput()))
                .isInstanceOfSatisfying(SemanticFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.SEMANTIC_INDEX_UNAVAILABLE);
                    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(60));
                });
        client.server().verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFailureContracts")
    void preserves_each_typed_query_failure_even_when_the_http_status_is_transient(
            String code,
            HttpStatus status,
            SemanticFailure.Kind expectedKind) {
        TestClient client = testClient();
        client.server().expect(once(), requestTo(BASE_URL + "/v1/code-facts/search"))
                .andRespond(withStatus(status)
                        .body("{\"code\":\"" + code + "\",\"retryable\":false}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.semantic().searchCodeFacts(searchInput()))
                .isInstanceOfSatisfying(SemanticFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(expectedKind));
        client.server().verify();
    }

    private static Stream<Arguments> queryFailureContracts() {
        return Stream.of(
                Arguments.of("REPOSITORY_NOT_FOUND", HttpStatus.NOT_FOUND,
                        SemanticFailure.Kind.REPOSITORY_NOT_FOUND),
                Arguments.of("INDEX_NOT_READY", HttpStatus.SERVICE_UNAVAILABLE,
                        SemanticFailure.Kind.INDEX_NOT_READY),
                Arguments.of("INDEX_CONTRACT_MISMATCH", HttpStatus.SERVICE_UNAVAILABLE,
                        SemanticFailure.Kind.INDEX_CONTRACT_MISMATCH),
                Arguments.of("CODE_FACT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        SemanticFailure.Kind.CODE_FACT_NOT_FOUND),
                Arguments.of("CODE_FACT_KIND_UNSUPPORTED", HttpStatus.BAD_REQUEST,
                        SemanticFailure.Kind.CODE_FACT_KIND_UNSUPPORTED),
                Arguments.of("REQUEST_INVALID", HttpStatus.BAD_REQUEST,
                        SemanticFailure.Kind.INVALID_ARGUMENT),
                Arguments.of("SEMANTIC_INDEX_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                        SemanticFailure.Kind.SEMANTIC_INDEX_UNAVAILABLE));
    }

    private static Stream<Arguments> sourceToolContracts() {
        String method = "{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                + "\",\"packageName\":\"com.example.pay\",\"className\":\"PaymentService\","
                + "\"sourceFile\":\"src/main/java/com/example/pay/PaymentService.java\","
                + "\"methodName\":\"pay\",\"parameterTypes\":[\"java.lang.String\"]}";
        String pagedMethod = method.substring(0, method.length() - 1) + ",\"offset\":4,\"limit\":20}";
        String callGraph = method.substring(0, method.length() - 1) + ",\"depth\":2,\"depthTwoNodeBudget\":25}";
        return Stream.of(
                Arguments.of("entry points", HttpMethod.GET,
                        "/v1/repositories/payment-service/entry-points?revision=" + REVISION,
                        Optional.empty(), (SourceCall) client -> client.listEntryPoints(
                                new ListEntryPointsInput(REPOSITORY_ID, REVISION))),
                Arguments.of("route lookup", HttpMethod.POST, "/v1/api-routes/lookup",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"httpMethod\":\"GET\",\"path\":\"/payments\"}"),
                        (SourceCall) client -> client.lookupApiRoute(
                                new LookupApiRouteInput(REPOSITORY_ID, REVISION, "GET", "/payments"))),
                Arguments.of("route suggestion", HttpMethod.POST, "/v1/api-routes/suggest",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"httpMethod\":\"GET\",\"path\":\"/pay\"}"),
                        (SourceCall) client -> client.suggestApiRoute(
                                new SuggestApiRouteInput(REPOSITORY_ID, REVISION, "GET", "/pay"))),
                Arguments.of("outgoing call graph", HttpMethod.POST, "/v1/analyses/call-graphs/outgoing",
                        body(callGraph), (SourceCall) client -> client.outgoingCallGraph(outgoing())),
                Arguments.of("incoming call graph", HttpMethod.POST, "/v1/analyses/call-graphs/incoming",
                        body(callGraph), (SourceCall) client -> client.incomingCallGraph(incoming())),
                Arguments.of("code-fact search", HttpMethod.POST, "/v1/code-facts/search",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"query\":\"refund\"}"),
                        (SourceCall) client -> client.searchCodeFacts(searchInput())),
                Arguments.of("code-fact get", HttpMethod.POST, "/v1/code-facts/get",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"factId\":\"" + FACT_ID + "\"}"),
                        (SourceCall) client -> client.getCodeFact(
                                new GetCodeFactInput(REPOSITORY_ID, REVISION, FACT_ID))),
                Arguments.of("event listeners", HttpMethod.POST, "/v1/discovery/event-listeners",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"eventType\":\"com.example.pay.PaymentMade\"}"),
                        (SourceCall) client -> client.discoverEventListeners(
                                new DiscoverEventListenersInput(REPOSITORY_ID, REVISION,
                                        "com.example.pay.PaymentMade", null, null))), // cs-allow optional query fields
                Arguments.of("method implementations", HttpMethod.POST,
                        "/v1/discovery/method-implementations", body(pagedMethod),
                        (SourceCall) client -> client.discoverMethodImplementations(methodImplementations())),
                Arguments.of("type members", HttpMethod.POST, "/v1/discovery/type-members",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"packageName\":\"com.example.pay\",\"className\":\"PaymentService\","
                                + "\"sourceFile\":\"src/main/java/com/example/pay/PaymentService.java\","
                                + "\"kinds\":[\"METHOD\"],\"offset\":4,\"limit\":20}"),
                        (SourceCall) client -> client.discoverTypeMembers(typeMembers())),
                Arguments.of("internal references", HttpMethod.POST, "/v1/discovery/internal-references",
                        body(pagedMethod), (SourceCall) client -> client.findInternalReferences(internalReferences())),
                Arguments.of("evidence source", HttpMethod.POST, "/v1/discovery/evidence-source",
                        body(method), (SourceCall) client -> client.getEvidenceSource(evidenceSource())),
                Arguments.of("method source", HttpMethod.POST, "/v1/discovery/method-source",
                        body(method), (SourceCall) client -> client.getMethodSource(methodSource())),
                Arguments.of("source segment", HttpMethod.POST, "/v1/discovery/source-segment",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"packageName\":\"com.example.pay\",\"className\":\"PaymentService\","
                                + "\"sourceFile\":\"src/main/java/com/example/pay/PaymentService.java\","
                                + "\"startLine\":10,\"startCharacter\":2,\"endLine\":20,\"endCharacter\":3}"),
                        (SourceCall) client -> client.getSourceSegment(sourceSegment())),
                Arguments.of("source symbol", HttpMethod.POST, "/v1/discovery/source-symbols/resolve",
                        body("{\"repositoryId\":\"payment-service\",\"revision\":\"" + REVISION
                                + "\",\"packageName\":\"com.example.pay\",\"className\":\"PaymentService\","
                                + "\"sourceFile\":\"src/main/java/com/example/pay/PaymentService.java\","
                                + "\"symbol\":\"fee\"}"),
                        (SourceCall) client -> client.resolveSourceSymbol(sourceSymbol())));
    }

    private static Optional<String> body(String value) {
        return Optional.of(value);
    }

    private static SearchCodeFactsInput searchInput() {
        return new SearchCodeFactsInput(REPOSITORY_ID, REVISION, "refund",
                null, null, null, null); // cs-allow optional filters are intentionally omitted
    }

    private static OutgoingCallGraphInput outgoing() {
        return new OutgoingCallGraphInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"), 2, 25);
    }

    private static IncomingCallGraphInput incoming() {
        return new IncomingCallGraphInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"), 2, 25);
    }

    private static DiscoverMethodImplementationsInput methodImplementations() {
        return new DiscoverMethodImplementationsInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"), 4, 20);
    }

    private static DiscoverTypeMembersInput typeMembers() {
        return new DiscoverTypeMembersInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", List.of(CodeFactKind.METHOD), 4, 20);
    }

    private static FindInternalReferencesInput internalReferences() {
        return new FindInternalReferencesInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"), 4, 20);
    }

    private static GetEvidenceSourceInput evidenceSource() {
        return new GetEvidenceSourceInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"));
    }

    private static GetMethodSourceInput methodSource() {
        return new GetMethodSourceInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "pay", List.of("java.lang.String"));
    }

    private static GetSourceSegmentInput sourceSegment() {
        return new GetSourceSegmentInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", 10, 2, 20, 3);
    }

    private static ResolveSourceSymbolInput sourceSymbol() {
        return new ResolveSourceSymbolInput(REPOSITORY_ID, REVISION, "com.example.pay", "PaymentService",
                "src/main/java/com/example/pay/PaymentService.java", "fee", null, null); // cs-allow optional position
    }

    private static String successEnvelope() {
        return "{\"repositoryId\":\"" + REPOSITORY_ID + "\",\"revision\":\"" + REVISION
                + "\",\"result\":{\"fact\":\"ok\"}}";
    }

    private static String stalePayload(String repositoryId, String requestedRevision, String currentRevision) {
        return "{\"code\":\"REVISION_OUTDATED\",\"repositoryId\":\"" + repositoryId
                + "\",\"requestedRevision\":\"" + requestedRevision + "\",\"currentRevision\":\""
                + currentRevision + "\",\"retryGuidance\":\"Retry with currentRevision.\"}";
    }

    private static void assertInvalidResponse(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SemanticFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(SemanticFailure.Kind.INVALID_RESPONSE));
    }

    private static TestClient testClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new SemanticSourceClient(builder.build()), server);
    }

    @FunctionalInterface
    private interface SourceCall {
        SemanticSourceClient.SourceResult<JsonNode> invoke(SemanticSourceClient client);
    }

    private record TestClient(SemanticSourceClient semantic, MockRestServiceServer server) {
    }
}
