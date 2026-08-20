package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.json.SemanticResultJsonWriter;
import com.java.system.sessionagent.semantic.dto.EvidenceIdentity;
import com.java.system.sessionagent.semantic.dto.InternalReferenceTarget;
import com.java.system.sessionagent.semantic.dto.MethodTarget;
import com.java.system.sessionagent.semantic.dto.ProviderDtos;
import com.java.system.sessionagent.semantic.dto.SemanticIdentity;
import com.java.system.sessionagent.semantic.dto.SemanticLocation;
import com.java.system.sessionagent.semantic.tool.input.ConceptKind;
import com.java.system.sessionagent.semantic.tool.input.ConceptTerm;
import com.java.system.sessionagent.semantic.tool.input.DiscoverConceptsInput;
import com.java.system.sessionagent.semantic.tool.input.DiscoverEventListenersInput;
import com.java.system.sessionagent.semantic.tool.input.DiscoverMethodImplementationsInput;
import com.java.system.sessionagent.semantic.tool.input.DiscoverTypeMembersInput;
import com.java.system.sessionagent.semantic.tool.input.FindInternalReferencesInput;
import com.java.system.sessionagent.semantic.tool.input.GetEvidenceSourceInput;
import com.java.system.sessionagent.semantic.tool.input.GetMethodSourceInput;
import com.java.system.sessionagent.semantic.tool.input.GetSourceSegmentInput;
import com.java.system.sessionagent.semantic.tool.input.IncomingCallGraphInput;
import com.java.system.sessionagent.semantic.tool.input.ListEntryPointsInput;
import com.java.system.sessionagent.semantic.tool.input.LookupApiRouteInput;
import com.java.system.sessionagent.semantic.tool.input.MemberKind;
import com.java.system.sessionagent.semantic.tool.input.OutgoingCallGraphInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveConceptInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveSourceSymbolInput;
import com.java.system.sessionagent.semantic.tool.input.SuggestApiRouteInput;
import com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

class SemanticSourceClientTest {

    @Test
    void recognizes_only_a_scoped_revision_mismatch_conflict_as_revision_changed() {
        ClientFixture acceptedFixture = fixture();
        acceptedFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":null,"expectedRevision":"revision-42","currentRevision":"revision-43","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure accepted = assertThrows(SemanticFailure.class,
                () -> acceptedFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.REVISION_CHANGED, accepted.kind());
        acceptedFixture.server().verify();

        ClientFixture missingCurrentRevisionFixture = fixture();
        missingCurrentRevisionFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":null,"expectedRevision":"revision-42","currentRevision":null,"target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure missingCurrentRevision = assertThrows(SemanticFailure.class,
                () -> missingCurrentRevisionFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, missingCurrentRevision.kind());
        missingCurrentRevisionFixture.server().verify();

        ClientFixture blankCurrentRevisionFixture = fixture();
        blankCurrentRevisionFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":null,"expectedRevision":"revision-42","currentRevision":"   ","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure blankCurrentRevision = assertThrows(SemanticFailure.class,
                () -> blankCurrentRevisionFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, blankCurrentRevision.kind());
        blankCurrentRevisionFixture.server().verify();

        ClientFixture unchangedCurrentRevisionFixture = fixture();
        unchangedCurrentRevisionFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":null,"expectedRevision":"revision-42","currentRevision":"revision-42","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure unchangedCurrentRevision = assertThrows(SemanticFailure.class,
                () -> unchangedCurrentRevisionFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, unchangedCurrentRevision.kind());
        unchangedCurrentRevisionFixture.server().verify();

        ClientFixture rejectedFixture = fixture();
        rejectedFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":"other-service","expectedRevision":"revision-42","currentRevision":"revision-43","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure rejected = assertThrows(SemanticFailure.class,
                () -> rejectedFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, rejected.kind());
        rejectedFixture.server().verify();

        ClientFixture missingExpectedRevisionFixture = fixture();
        missingExpectedRevisionFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REPOSITORY_REVISION_MISMATCH","message":"changed","repoId":null,"expectedRevision":null,"currentRevision":"revision-43","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure missingExpectedRevision = assertThrows(SemanticFailure.class,
                () -> missingExpectedRevisionFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, missingExpectedRevision.kind());
        missingExpectedRevisionFixture.server().verify();

        ClientFixture otherConflictFixture = fixture();
        otherConflictFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"UNSUPPORTED_OPERATION","message":"changed","repoId":"payment-service","expectedRevision":"revision-42","currentRevision":"revision-43","target":null,"candidates":null,"requestId":"request-1"}
                        """));

        SemanticFailure otherConflict = assertThrows(SemanticFailure.class,
                () -> otherConflictFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, otherConflict.kind());
        otherConflictFixture.server().verify();
    }

    @Test
    void classifies_a_provider_rejected_source_request_as_correctable_input() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"REQUEST_INVALID","message":"request body is invalid","repoId":null,
                        "expectedRevision":null,"currentRevision":null,"target":null,"candidates":[],
                        "requestId":"request-1"}
                        """));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> fixture.client().getSourceSegment(
                        new GetSourceSegmentInput("payment-service", location(), null)));

        assertEquals(SemanticFailure.Kind.INVALID_INPUT, failure.kind());
        fixture.server().verify();
    }

    @TestFactory
    Stream<DynamicTest> uses_the_provider_wire_contract_for_every_source_operation() {
        MethodTarget target = target();
        SemanticLocation location = location();
        String targetJson = "{\"sourceType\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Payments\"},\"sourceFile\":\"src/Payments.java\"},\"methodName\":\"pay\",\"parameterTypes\":[]}";
        String locationJson = "{\"sourceFile\":\"src/Payments.java\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}}}";
        String rangeJson = "{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}}";
        String scope = "\"repoId\":\"payment-service\",\"analyzedRevision\":\"revision-42\"";
        String page = "{\"offset\":0,\"limit\":50,\"returnedCount\":0,\"totalCount\":0,\"hasMore\":false}";
        String coverage = "{\"status\":\"COMPLETE\",\"scannedFileCount\":1,\"extractedFileCount\":1,\"syntaxFailedFileCount\":0}";
        String sourceType = "{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Payments\"},\"sourceFile\":\"src/Payments.java\"}";
        String candidate = "{\"identity\":{\"kind\":\"TYPE\",\"sourceType\":" + sourceType + "},\"displayValue\":\"Payments\",\"matchedTerms\":[\"payment\"],\"authority\":\"SYNTAX_RESOLVED\",\"evidence\":[],\"availableFollowUps\":[]}";
        String segment = "{\"location\":" + locationJson + ",\"content\":\"class Payments {}\"}";
        List<WireCase> cases = List.of(
                new WireCase("entry-points", GET, "/v1/repositories/payment-service/entry-points?expectedRevision=revision-42", "", "{" + scope + ",\"entryPoints\":[]}", client -> client.listEntryPoints(new ListEntryPointsInput("payment-service", null))),
                new WireCase("route-lookup", POST, "/v1/api-routes/lookup", "{\"apiPath\":\"/payments\",\"httpMethod\":\"GET\",\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\"}", "{\"candidates\":[],\"observations\":[]}", client -> client.lookupApiRoute(new LookupApiRouteInput("payment-service", "/payments", "GET"))),
                new WireCase("route-suggest", POST, "/v1/api-routes/suggest", "{\"apiPath\":\"/payments\",\"httpMethod\":\"GET\",\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"limit\":10}", "{\"candidates\":[],\"observations\":[]}", client -> client.suggestApiRoute(new SuggestApiRouteInput("payment-service", "/payments", "GET", null))),
                new WireCase("outgoing", POST, "/v1/analyses/call-graphs/outgoing", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"depth\":2,\"target\":" + targetJson + "}", graphResponse(targetJson, rangeJson), client -> client.outgoingCallGraph(new OutgoingCallGraphInput("payment-service", target, null))),
                new WireCase("incoming", POST, "/v1/analyses/call-graphs/incoming", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"depth\":2,\"target\":" + targetJson + "}", graphResponse(targetJson, rangeJson), client -> client.incomingCallGraph(new IncomingCallGraphInput("payment-service", target, null))),
                new WireCase("concepts", POST, "/v1/discovery/concepts", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"terms\":[{\"value\":\"payment\",\"matchMode\":\"TOKEN_PREFIX\"}],\"kinds\":[\"TYPE\"],\"operator\":\"ALL\",\"packagePrefix\":\"com.example\",\"offset\":0,\"limit\":50}", "{" + scope + ",\"normalizedTerms\":[\"payment\"],\"searchedKinds\":[\"TYPE\"],\"supportedKinds\":[\"TYPE\"],\"limitations\":[],\"candidates\":[" + candidate + "],\"page\":" + page + ",\"coverage\":" + coverage + ",\"issueSummaries\":[],\"availableFollowUps\":[],\"unavailableFollowUps\":[]}", client -> client.discoverConcepts(new DiscoverConceptsInput("payment-service", List.of(new ConceptTerm("payment", null)), List.of(ConceptKind.TYPE), "com.example", null, null))),
                new WireCase("resolve-concept", POST, "/v1/discovery/concepts/resolve", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"identity\":{\"kind\":\"TYPE\",\"sourceType\":" + sourceType + "}}", "{" + scope + ",\"candidate\":" + candidate + "}", client -> client.resolveConcept(new ResolveConceptInput("payment-service", new SemanticIdentity.Type(sourceTypePayload())))),
                new WireCase("event-listeners", POST, "/v1/discovery/event-listeners", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"eventType\":\"com.example.Event\",\"offset\":0,\"limit\":50}", "{" + scope + ",\"requestedEventType\":\"com.example.Event\",\"candidates\":[{\"target\":" + targetJson + ",\"listenerAnnotations\":[],\"sourceRange\":" + rangeJson + ",\"availableFollowUps\":[]}],\"page\":" + page + ",\"observationSummaries\":[],\"availableFollowUps\":[]}", client -> client.discoverEventListeners(new DiscoverEventListenersInput("payment-service", "com.example.Event", null, null))),
                new WireCase("implementations", POST, "/v1/discovery/method-implementations", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"declarationTarget\":" + targetJson + "}", "{\"repoId\":\"payment-service\",\"revision\":\"revision-42\",\"requestedTarget\":" + targetJson + ",\"candidates\":[{\"target\":" + targetJson + ",\"primary\":true,\"qualifiers\":[],\"profiles\":[],\"availableFollowUps\":[]}],\"limits\":{\"limit\":50,\"returnedCount\":1,\"totalCount\":1,\"truncated\":false},\"resolution\":{\"status\":\"COMPLETE\",\"issueSummaries\":[]}}", client -> client.discoverMethodImplementations(new DiscoverMethodImplementationsInput("payment-service", target))),
                new WireCase("type-members", POST, "/v1/discovery/type-members", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"sourceType\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Payments\"},\"sourceFile\":\"src/Payments.java\"},\"memberKinds\":[\"METHOD\"],\"namePrefix\":\"pay\",\"offset\":0,\"limit\":50}", "{" + scope + ",\"sourceType\":" + sourceType + ",\"typeKind\":\"CLASS\",\"annotations\":[],\"implementedTypes\":[],\"extendedTypes\":[],\"members\":[{\"kind\":\"METHOD\",\"target\":" + targetJson + ",\"availableFollowUps\":[]}],\"page\":" + page + ",\"coverage\":" + coverage + ",\"availableFollowUps\":[]}", client -> client.discoverTypeMembers(new DiscoverTypeMembersInput("payment-service", target.sourceType(), List.of(MemberKind.METHOD), "pay", null, null))),
                new WireCase("internal-references", POST, "/v1/discovery/internal-references", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"target\":{\"kind\":\"TYPE\",\"identity\":" + sourceType + "},\"offset\":0,\"limit\":50}", "{" + scope + ",\"status\":\"COMPLETE\",\"targetDeclaration\":{\"target\":{\"kind\":\"TYPE\",\"identity\":" + sourceType + "},\"declarationRange\":" + rangeJson + ",\"availableFollowUps\":[]},\"totalReferenceCount\":0,\"referenceGroups\":[],\"page\":" + page + ",\"issueSummaries\":[],\"availableFollowUps\":[]}", client -> client.findInternalReferences(new FindInternalReferencesInput("payment-service", new InternalReferenceTarget.Type(sourceTypePayload()), null, null))),
                new WireCase("evidence-source", POST, "/v1/discovery/evidence-source", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"identity\":{\"kind\":\"ANNOTATION_SQL\",\"statementIdentity\":{\"statementKey\":{\"namespace\":\"payments\",\"statementId\":\"pay\"},\"resourcePath\":\"src/Payments.xml\",\"documentOrdinal\":0,\"representation\":\"ANNOTATION\"}}}", "{" + scope + ",\"identity\":{\"kind\":\"ANNOTATION_SQL\",\"statementIdentity\":{\"statementKey\":{\"namespace\":\"payments\",\"statementId\":\"pay\"},\"resourcePath\":\"src/Payments.xml\",\"documentOrdinal\":0,\"representation\":\"ANNOTATION\"}},\"location\":" + locationJson + ",\"segment\":" + segment + ",\"availableFollowUps\":[]}", client -> client.getEvidenceSource(new GetEvidenceSourceInput("payment-service", new EvidenceIdentity.AnnotationSql(statementIdentity())))),
                new WireCase("method-source", POST, "/v1/discovery/method-source", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"target\":" + targetJson + "}", "{" + scope + ",\"declarationLocation\":" + locationJson + ",\"segment\":" + segment + ",\"availableFollowUps\":[]}", client -> client.getMethodSource(new GetMethodSourceInput("payment-service", target))),
                new WireCase("source-segment", POST, "/v1/discovery/source-segment", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"location\":" + locationJson + ",\"contextLines\":0}", "{" + scope + ",\"segment\":" + segment + ",\"contextTruncated\":false,\"availableFollowUps\":[]}", client -> client.getSourceSegment(new GetSourceSegmentInput("payment-service", location, null))),
                new WireCase("source-symbol", POST, "/v1/discovery/source-symbols/resolve", "{\"repoId\":\"payment-service\",\"expectedRevision\":\"revision-42\",\"context\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Payments\"},\"sourceFile\":\"src/Payments.java\",\"method\":{\"name\":\"pay\",\"parameterTypes\":[]}},\"symbol\":\"payment\",\"position\":{\"line\":0,\"character\":0}}", "{" + scope + ",\"status\":\"RESOLVED\",\"contextCandidates\":[],\"contextCandidateLimits\":{\"limit\":10,\"returnedCount\":0,\"totalCount\":0,\"truncated\":false},\"candidates\":[],\"issues\":[]}", client -> client.resolveSourceSymbol(new ResolveSourceSymbolInput("payment-service", "payment", sourceSymbolContext(), new ProviderDtos.Position(0, 0))))
        );
        return cases.stream().map(wireCase -> DynamicTest.dynamicTest(wireCase.name(), () -> verify(wireCase)));
    }

    @Test
    void decodes_a_provider_field_concept_with_its_declared_type_and_follow_up() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {
                          "repoId":"payment-service",
                          "analyzedRevision":"revision-42",
                          "normalizedTerms":["fee"],
                          "searchedKinds":["FIELD"],
                          "supportedKinds":["TYPE","METHOD","FIELD"],
                          "limitations":["SOURCE_BODY_NOT_SEARCHED"],
                          "candidates":[{
                            "identity":{"kind":"FIELD","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example.payment","className":"PaymentFeeCalculator"},"sourceFile":"src/main/java/com/example/payment/PaymentFeeCalculator.java"},"name":"feeFormulaEvaluator"}},
                            "displayValue":"feeFormulaEvaluator",
                            "matchedTerms":["fee"],
                            "authority":"SYNTAX_DECLARED",
                            "details":{"kind":"FIELD","declaredType":{"kind":"NAMED","writtenType":"FeeFormulaEvaluator","simpleTypeName":"FeeFormulaEvaluator","resolvedJavaType":{"packageName":"com.example.payment","className":"FeeFormulaEvaluator"},"sourceDefined":true}},
                            "evidence":[{"identity":{"kind":"FIELD","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example.payment","className":"PaymentFeeCalculator"},"sourceFile":"src/main/java/com/example/payment/PaymentFeeCalculator.java"},"name":"feeFormulaEvaluator"}}}],
                            "availableFollowUps":[{"operation":"GET_TYPE_MEMBERS","api":{"method":"POST","path":"/v1/discovery/type-members","operationId":"discoverTypeMembers"},"request":{"repoId":"payment-service","expectedRevision":"revision-42","sourceType":{"javaType":{"packageName":"com.example.payment","className":"PaymentFeeCalculator"},"sourceFile":"src/main/java/com/example/payment/PaymentFeeCalculator.java"},"memberKinds":["METHOD","FIELD"],"namePrefix":null,"offset":0,"limit":50}}]
                          }],
                          "page":{"offset":0,"limit":50,"returnedCount":1,"totalCount":1,"hasMore":false},
                          "coverage":{"status":"COMPLETE","scannedFileCount":6,"extractedFileCount":6,"syntaxFailedFileCount":0},
                          "issueSummaries":[],
                          "availableFollowUps":[],
                          "unavailableFollowUps":[]
                        }
                        """, MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<ProviderDtos.DiscoverConceptsResponse> result = fixture.client()
                .discoverConcepts(new DiscoverConceptsInput("payment-service",
                        List.of(new ConceptTerm("fee", null)), List.of(ConceptKind.FIELD), null, null, null));

        ProviderDtos.ConceptCandidateResponse candidate = result.response().candidates().getFirst();
        assertTrue(candidate.details().orElseThrow()
                instanceof ProviderDtos.FieldConceptCandidateDetailsResponse);
        assertEquals("GET_TYPE_MEMBERS", candidate.availableFollowUps().getFirst().operation());
        ToolExecution execution = new ToolExecution(new ToolName("codebase_discover_concepts"), "v1",
                ToolKind.SOURCE, "{}", Optional.of("payment-service"), Optional.of("revision-42"),
                new SemanticResultJsonWriter().write(result.response()), true);
        new ToolResultEnvelopeFactory().validate(execution);
        fixture.server().verify();
    }

    @Test
    void reads_current_revision_immediately_before_the_typed_entry_point_request() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticSourceClient client = new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","entryPoints":[]}
                        """, MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<?> result = client.listEntryPoints(new ListEntryPointsInput("payment-service", null));

        assertEquals("revision-42", result.revision().value());
        server.verify();
    }

    @Test
    void injects_only_the_current_revision_into_a_typed_route_request() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticSourceClient client = new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test/v1/api-routes/lookup"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"apiPath":"/payments","httpMethod":"GET","repoId":"payment-service","expectedRevision":"revision-42"}
                        """))
                .andRespond(withSuccess("{" + "\"candidates\":[],\"observations\":[]}" , MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<?> result = client.lookupApiRoute(
                new LookupApiRouteInput("payment-service", "/payments", "GET"));

        assertEquals("revision-42", result.revision().value());
        server.verify();
    }

    @Test
    void rejects_unknown_provider_response_fields_without_leaking_the_response() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticSourceClient client = new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","entryPoints":[{"className":"Payments","packageName":"com.example","packagePath":"src","description":"entry","basePaths":[],"methods":[],"unrecognized":"must-fail"}]}
                        """, MediaType.APPLICATION_JSON));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> client.listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
        server.verify();
    }

    @Test
    void rejects_missing_or_blank_required_provider_scalars_before_a_tool_result_is_created() {
        ClientFixture missingClassName = fixture();
        missingClassName.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","entryPoints":[{"packageName":"com.example","packagePath":"src","description":"entry","basePaths":[],"methods":[]}]}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> missingClassName.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));
        missingClassName.server().verify();

        ClientFixture blankRouteScalar = fixture();
        blankRouteScalar.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/lookup"))
                .andRespond(withSuccess("""
                        {"candidates":[{"repoId":"payment-service","analyzedRevision":"revision-42","httpMethod":" ","routeTemplate":"/payments","packageName":"com.example","className":"Payments","methodName":"pay","analysisTarget":{"status":"RESOLVED","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Payments"},"sourceFile":"src/Payments.java"},"methodName":"pay","parameterTypes":[]},"candidates":[],"reasonCode":"OK"},"matchReasons":[]}],"observations":[]}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> blankRouteScalar.client().lookupApiRoute(
                new LookupApiRouteInput("payment-service", "/payments", "GET")));
        blankRouteScalar.server().verify();
    }

    @Test
    void decodes_a_provider_shaped_call_graph_without_losing_nested_fields() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticSourceClient client = new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"status":"COMPLETE","analyzedRevision":"revision-42","rootNodeId":"root",
                         "traversal":{"requestedDepth":2,"expandedNodeCount":1,"nodeBudget":100,"rootDirectCallsComplete":true,"limitReason":null},
                         "nodes":[{"nodeId":"root","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Payments"},"sourceFile":"src/Payments.java"},"methodName":"pay","parameterTypes":[]},"externalSymbol":null,"contentState":"RESOLVED","traversalState":"EXPANDED","dispatchKind":"DIRECT","declarationRange":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"availableFollowUps":[]}],
                         "edges":[],"warnings":[],"errors":[]}
                        """, MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<?> result = client.outgoingCallGraph(
                new OutgoingCallGraphInput("payment-service", target(), null));

        String normalized = new SemanticResultJsonWriter().write(result.response());
        assertTrue(normalized.contains("rootNodeId"));
        assertTrue(normalized.contains("expandedNodeCount"));
        server.verify();
    }

    @Test
    void decodes_enum_constants_and_record_components_from_type_member_results() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/type-members"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42",
                         "sourceType":{"javaType":{"packageName":"com.example","className":"PaymentMethod"},"sourceFile":"src/PaymentMethod.java"},
                         "typeKind":"ENUM","annotations":[],"implementedTypes":[],"extendedTypes":[],
                         "members":[
                           {"kind":"ENUM_CONSTANT","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example","className":"PaymentMethod"},"sourceFile":"src/PaymentMethod.java"},"name":"CREDIT_CARD"},
                            "declarationRange":{"start":{"line":1,"character":4},"end":{"line":1,"character":15}},"annotations":[],"availableFollowUps":[]},
                           {"kind":"RECORD_COMPONENT","identity":{"scope":"TYPE","ownerType":{"javaType":{"packageName":"com.example","className":"PaymentMethod"},"sourceFile":"src/PaymentMethod.java"},"name":"label"},
                            "writtenType":"String","resolvedType":"java.lang.String","declarationRange":{"start":{"line":0,"character":20},"end":{"line":0,"character":32}},"annotations":[],"availableFollowUps":[]}
                         ],
                         "page":{"offset":0,"limit":50,"returnedCount":2,"totalCount":2,"hasMore":false},
                         "coverage":{"status":"COMPLETE","scannedFileCount":1,"extractedFileCount":1,"syntaxFailedFileCount":0},
                         "availableFollowUps":[]}
                        """, MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<?> result = fixture.client().discoverTypeMembers(
                new DiscoverTypeMembersInput("payment-service",
                        new MethodTarget.SourceType(new MethodTarget.JavaType("com.example", "PaymentMethod"),
                                "src/PaymentMethod.java"),
                        List.of(MemberKind.METHOD), null, null, null));

        String json = new SemanticResultJsonWriter().write(result.response());
        assertTrue(json.contains("\"kind\":\"ENUM_CONSTANT\""));
        assertTrue(json.contains("\"kind\":\"RECORD_COMPONENT\""));
        fixture.server().verify();
    }

    @Test
    void rejects_a_top_level_scope_mismatch() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withSuccess("{\"repoId\":\"other-service\",\"analyzedRevision\":\"revision-42\",\"entryPoints\":[]}", MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> fixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));
        fixture.server().verify();
    }

    @Test
    void reports_a_response_revision_change_without_treating_it_as_a_provider_contract_error() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withSuccess("{\"repoId\":\"payment-service\",\"analyzedRevision\":\"revision-43\",\"entryPoints\":[]}", MediaType.APPLICATION_JSON));

        SemanticFailure failure = assertThrows(SemanticFailure.class,
                () -> fixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.REVISION_CHANGED, failure.kind());
        fixture.server().verify();
    }

    @Test
    void keeps_missing_and_oversized_retry_after_hints_transient() {
        ClientFixture missingHint = fixture();
        missingHint.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        ClientFixture oversizedHint = fixture();
        oversizedHint.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "999999999999999999999999999999999999999999999999"));

        SemanticFailure missing = assertThrows(SemanticFailure.class,
                () -> missingHint.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));
        SemanticFailure oversized = assertThrows(SemanticFailure.class,
                () -> oversizedHint.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));

        assertEquals(SemanticFailure.Kind.TRANSIENT, missing.kind());
        assertTrue(missing.retryAfter().isEmpty());
        assertEquals(SemanticFailure.Kind.TRANSIENT, oversized.kind());
        assertEquals(java.time.Duration.ofSeconds(60), oversized.retryAfter().orElseThrow());
        missingHint.server().verify();
        oversizedHint.server().verify();
    }

    @Test
    void rejects_a_nested_route_candidate_scope_mismatch() {
        ClientFixture fixture = fixture();
        fixture.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/lookup"))
                .andRespond(withSuccess("{\"candidates\":[{\"repoId\":\"other-service\",\"analyzedRevision\":\"revision-42\"}],\"observations\":[]}", MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> fixture.client().lookupApiRoute(
                new LookupApiRouteInput("payment-service", "/payments", "GET")));
        fixture.server().verify();
    }

    @Test
    void rejects_complete_identity_and_requested_range_echo_mismatches() {
        ClientFixture identityFixture = fixture();
        identityFixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts/resolve"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","candidate":{
                        "identity":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example","className":"OtherPayments"},"sourceFile":"src/Payments.java"}},
                        "displayValue":"OtherPayments","matchedTerms":[],"authority":"SYNTAX_RESOLVED","evidence":[],"availableFollowUps":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> identityFixture.client().resolveConcept(
                new ResolveConceptInput("payment-service", new SemanticIdentity.Type(sourceTypePayload()))));
        identityFixture.server().verify();

        ClientFixture evidenceFixture = fixture();
        evidenceFixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/evidence-source"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42",
                        "identity":{"kind":"ANNOTATION_SQL","statementIdentity":{"statementKey":{"namespace":"payments","statementId":"other"},
                        "resourcePath":"src/Payments.xml","documentOrdinal":0,"representation":"ANNOTATION"}},
                        "location":{"sourceFile":"src/Payments.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},
                        "segment":{"location":{"sourceFile":"src/Payments.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},"content":"x"},"availableFollowUps":[]}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> evidenceFixture.client().getEvidenceSource(
                new GetEvidenceSourceInput("payment-service", new EvidenceIdentity.AnnotationSql(statementIdentity()))));
        evidenceFixture.server().verify();

        ClientFixture rangeFixture = fixture();
        rangeFixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","segment":{
                        "location":{"sourceFile":"src/Payments.java","range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}}},
                        "content":"x"},"contextTruncated":false,"availableFollowUps":[]}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> rangeFixture.client().getSourceSegment(
                new GetSourceSegmentInput("payment-service", location(), null)));
        rangeFixture.server().verify();
    }

    @Test
    void rejects_unknown_discriminators_and_malformed_success_json() {
        ClientFixture discriminatorFixture = fixture();
        discriminatorFixture.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts/resolve"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","analyzedRevision":"revision-42","candidate":{
                        "identity":{"kind":"UNKNOWN"},"displayValue":"Unknown","matchedTerms":[],
                        "authority":"SYNTAX_RESOLVED","evidence":[],"availableFollowUps":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> discriminatorFixture.client().resolveConcept(
                new ResolveConceptInput("payment-service", new SemanticIdentity.Type(sourceTypePayload()))));
        discriminatorFixture.server().verify();

        ClientFixture malformedFixture = fixture();
        malformedFixture.server().expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service/entry-points?expectedRevision=revision-42"))
                .andRespond(withSuccess("{\"repoId\":", MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> malformedFixture.client().listEntryPoints(new ListEntryPointsInput("payment-service", null)));
        malformedFixture.server().verify();
    }

    private static void verify(WireCase wireCase) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticSourceClient client = new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient));
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test" + wireCase.path()))
                .andExpect(method(wireCase.method()))
                .andExpect(request -> {
                    if (POST.equals(wireCase.method())) {
                        content().json(wireCase.request()).match(request);
                    }
                })
                .andRespond(withSuccess(wireCase.response(), MediaType.APPLICATION_JSON));

        SemanticSourceClient.SourceResult<?> result = wireCase.call().apply(client);

        assertEquals("revision-42", result.revision().value());
        assertTrue(result.response().getClass().getPackageName().contains("semantic.dto"));
        server.verify();
    }

    private static ClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service",\
                        "currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        return new ClientFixture(new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)), server);
    }

    private static void assertInvalidResponse(org.junit.jupiter.api.function.Executable call) {
        SemanticFailure failure = assertThrows(SemanticFailure.class, call);
        assertEquals(SemanticFailure.Kind.INVALID_RESPONSE, failure.kind());
    }

    private static MethodTarget target() {
        return new MethodTarget(new MethodTarget.SourceType(
                new MethodTarget.JavaType("com.example", "Payments"), "src/Payments.java"), "pay", List.of());
    }

    private static String graphResponse(String target, String range) {
        return "{\"status\":\"COMPLETE\",\"analyzedRevision\":\"revision-42\",\"rootNodeId\":\"root\","
                + "\"traversal\":{\"requestedDepth\":2,\"expandedNodeCount\":1,\"nodeBudget\":100,\"rootDirectCallsComplete\":true,\"limitReason\":null},"
                + "\"nodes\":[{\"nodeId\":\"root\",\"target\":" + target + ",\"externalSymbol\":null,\"contentState\":\"RESOLVED\",\"traversalState\":\"EXPANDED\",\"dispatchKind\":\"DIRECT\",\"declarationRange\":" + range + ",\"availableFollowUps\":[]}],"
                + "\"edges\":[],\"warnings\":[{\"code\":\"INCOMING_CALLER_REJECTED\","
                + "\"message\":\"incoming caller rejection count: 2\",\"nodeId\":\"root\","
                + "\"callExpression\":null,\"callSite\":null,\"candidates\":[],\"availableFollowUps\":[]}],"
                + "\"errors\":[]}";
    }

    private static SemanticLocation location() {
        return new SemanticLocation("src/Payments.java", new SemanticLocation.Range(
                new SemanticLocation.Position(0, 0), new SemanticLocation.Position(0, 1)));
    }

    private static ProviderDtos.SourceTypeIdentityPayload sourceTypePayload() {
        return new ProviderDtos.SourceTypeIdentityPayload(
                new ProviderDtos.JavaTypeIdentityPayload("com.example", "Payments"), "src/Payments.java");
    }

    private static ProviderDtos.MapperStatementIdentityPayload statementIdentity() {
        return new ProviderDtos.MapperStatementIdentityPayload(
                new ProviderDtos.MapperStatementKeyPayload("payments", "pay"), "src/Payments.xml", Optional.empty(),
                0, "ANNOTATION");
    }

    private static ProviderDtos.SourceSymbolContextPayload sourceSymbolContext() {
        return new ProviderDtos.SourceSymbolContextPayload(
                new ProviderDtos.JavaTypeIdentityPayload("com.example", "Payments"), Optional.of("src/Payments.java"),
                Optional.of(new ProviderDtos.SourceSymbolMethodContextPayload("pay", List.of())));
    }

    private record WireCase(String name, org.springframework.http.HttpMethod method, String path, String request,
                            String response, java.util.function.Function<SemanticSourceClient, SemanticSourceClient.SourceResult<?>> call) { }

    private record ClientFixture(SemanticSourceClient client, MockRestServiceServer server) { }
}
