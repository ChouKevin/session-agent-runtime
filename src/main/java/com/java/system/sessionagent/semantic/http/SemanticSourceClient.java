package com.java.system.sessionagent.semantic.http;

import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Strict pass-through adapter for Query's revision-pinned flat request contract. */
public final class SemanticSourceClient {
    private static final BigInteger MAX_RETRY_AFTER_SECONDS = BigInteger.valueOf(60);
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final RestClient restClient;

    public SemanticSourceClient(RestClient restClient) { this.restClient = strict(Objects.requireNonNull(restClient, "Semantic service RestClient must not be null")); }
    public SourceResult<JsonNode> listEntryPoints(ListEntryPointsInput input) { return get(input); }
    public SourceResult<JsonNode> lookupApiRoute(LookupApiRouteInput input) { return post("/v1/api-routes/lookup", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> suggestApiRoute(SuggestApiRouteInput input) { return post("/v1/api-routes/suggest", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> outgoingCallGraph(OutgoingCallGraphInput input) { return post("/v1/analyses/call-graphs/outgoing", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> incomingCallGraph(IncomingCallGraphInput input) { return post("/v1/analyses/call-graphs/incoming", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> searchCodeFacts(SearchCodeFactsInput input) { return post("/v1/code-facts/search", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> getCodeFact(GetCodeFactInput input) { return post("/v1/code-facts/get", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> discoverEventListeners(DiscoverEventListenersInput input) { return post("/v1/discovery/event-listeners", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> discoverMethodImplementations(DiscoverMethodImplementationsInput input) { return post("/v1/discovery/method-implementations", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> discoverTypeMembers(DiscoverTypeMembersInput input) { return post("/v1/discovery/type-members", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> findInternalReferences(FindInternalReferencesInput input) { return post("/v1/discovery/internal-references", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> getEvidenceSource(GetEvidenceSourceInput input) { return post("/v1/discovery/evidence-source", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> getMethodSource(GetMethodSourceInput input) { return post("/v1/discovery/method-source", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> getSourceSegment(GetSourceSegmentInput input) { return post("/v1/discovery/source-segment", input.repositoryId(), input.revision(), input); }
    public SourceResult<JsonNode> resolveSourceSymbol(ResolveSourceSymbolInput input) { return post("/v1/discovery/source-symbols/resolve", input.repositoryId(), input.revision(), input); }

    private SourceResult<JsonNode> get(ListEntryPointsInput input) {
        RepositoryRevision revision = revision(input.revision());
        try {
            GenerationEnvelope response = restClient.get().uri(builder -> builder.path("/v1/repositories/{repositoryId}/entry-points")
                    .queryParam("revision", input.revision()).build(input.repositoryId())).retrieve().body(GenerationEnvelope.class);
            return valid(input.repositoryId(), revision, response);
        } catch (RestClientResponseException exception) { throw classify(exception, input.repositoryId(), revision); }
        catch (ResourceAccessException exception) { throw SemanticFailure.semanticIndexUnavailable(Optional.empty(), exception); }
        catch (RestClientException exception) { throw SemanticHttpFailures.classify(exception); }
        catch (RuntimeException exception) { throw SemanticFailure.invalidResponse(); }
    }
    private SourceResult<JsonNode> post(String path, String repositoryId, String suppliedRevision, Object input) {
        RepositoryRevision revision = revision(suppliedRevision);
        try { return valid(repositoryId, revision, restClient.post().uri(path).body(flat(input)).retrieve().body(GenerationEnvelope.class)); }
        catch (RestClientResponseException exception) { throw classify(exception, repositoryId, revision); }
        catch (ResourceAccessException exception) { throw SemanticFailure.semanticIndexUnavailable(Optional.empty(), exception); }
        catch (RestClientException exception) { throw SemanticHttpFailures.classify(exception); }
        catch (RuntimeException exception) { throw SemanticFailure.invalidResponse(); }
    }
    private static ObjectNode flat(Object input) {
        ObjectNode request = MAPPER.valueToTree(input);
        request.properties().removeIf(entry -> entry.getValue().isNull());
        return request;
    }
    private static SourceResult<JsonNode> valid(String repositoryId, RepositoryRevision revision, GenerationEnvelope response) {
        if (Objects.isNull(response) || !repositoryId.equals(response.repositoryId()) || !revision.value().equals(response.revision()) || Objects.isNull(response.result())) { throw SemanticFailure.invalidResponse(); }
        return new SourceResult<>(revision, response.result());
    }
    private static RestClient strict(RestClient restClient) { return restClient.mutate().messageConverters(converters -> { converters.removeIf(SemanticSourceClient::jackson); converters.add(new JacksonJsonHttpMessageConverter(MAPPER)); }).build(); }
    private static boolean jackson(HttpMessageConverter<?> converter) { return converter instanceof JacksonJsonHttpMessageConverter; }
    private static RepositoryRevision revision(String value) { return new RepositoryRevision(value); }
    private static SemanticFailure classify(RestClientResponseException exception, String repositoryId, RepositoryRevision revision) {
        HttpStatusCode status = exception.getStatusCode();
        if (status.value() == 409) { return stale(exception, repositoryId, revision); }
        if (status.value() == 401 || status.value() == 403) { return SemanticFailure.forbidden(); }
        Optional<SemanticFailure> typedFailure = code(exception.getResponseBodyAsString(),
                retryAfter(exception.getResponseHeaders()), exception);
        if (typedFailure.isPresent()) { return typedFailure.orElseThrow(); }
        if (status.value() == 429 || status.value() == 503) {
            return SemanticFailure.semanticIndexUnavailable(retryAfter(exception.getResponseHeaders()), exception);
        }
        return SemanticFailure.invalidResponse();
    }
    private static SemanticFailure stale(RestClientResponseException exception, String repositoryId, RepositoryRevision revision) {
        try {
            RevisionOutdatedResponse response = MAPPER.readValue(exception.getResponseBodyAsString(), RevisionOutdatedResponse.class);
            if (!"REVISION_OUTDATED".equals(response.code()) || !repositoryId.equals(response.repositoryId())
                    || !revision.value().equals(response.requestedRevision())
                    || !StringUtils.hasText(response.currentRevision())
                    || revision.value().equals(response.currentRevision())
                    || !StringUtils.hasText(response.retryGuidance())) {
                return SemanticFailure.invalidResponse();
            }
            return SemanticFailure.revisionOutdated(response.repositoryId(), response.requestedRevision(), response.currentRevision(), response.retryGuidance());
        } catch (RuntimeException exception2) { return SemanticFailure.invalidResponse(); }
    }
    private static Optional<SemanticFailure> code(
            String body,
            Optional<Duration> retryAfter,
            RestClientResponseException cause) {
        try {
            String value = MAPPER.readTree(body).required("code").textValue();
            return Optional.ofNullable(switch (value) {
                case "REPOSITORY_NOT_FOUND" -> SemanticFailure.repositoryNotFound();
                case "INDEX_NOT_READY" -> SemanticFailure.indexNotReady();
                case "INVALID_ARGUMENT", "REQUEST_INVALID" -> SemanticFailure.invalidArgument();
                case "INDEX_CONTRACT_MISMATCH" -> SemanticFailure.indexContractMismatch();
                case "CODE_FACT_NOT_FOUND" -> SemanticFailure.codeFactNotFound();
                case "CODE_FACT_KIND_UNSUPPORTED" -> SemanticFailure.codeFactKindUnsupported();
                case "INVALID_QUERY" -> SemanticFailure.invalidQuery();
                case "SEMANTIC_INDEX_UNAVAILABLE" -> SemanticFailure.semanticIndexUnavailable(retryAfter, cause);
                default -> null; // cs-allow unknown provider codes are handled by HTTP status
            });
        } catch (RuntimeException exception) { return Optional.empty(); }
    }
    private static Optional<Duration> retryAfter(HttpHeaders headers) { return Optional.ofNullable(headers).map(value -> value.getFirst(HttpHeaders.RETRY_AFTER)).filter(value -> StringUtils.hasText(value) && value.chars().allMatch(character -> character >= '0' && character <= '9')).flatMap(SemanticSourceClient::duration); }
    private static Optional<Duration> duration(String value) { try { return Optional.of(Duration.ofSeconds(new BigInteger(value).min(MAX_RETRY_AFTER_SECONDS).longValueExact())); } catch (NumberFormatException | ArithmeticException exception) { return Optional.empty(); } }
    private record GenerationEnvelope(String repositoryId, String revision, JsonNode result) { }
    private record RevisionOutdatedResponse(String code, String repositoryId, String requestedRevision, String currentRevision, String retryGuidance) { }
    public record SourceResult<T>(RepositoryRevision revision, T response) { }
}
