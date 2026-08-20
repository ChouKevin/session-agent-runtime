package com.java.system.sessionagent.semantic.http;

import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
import com.java.system.sessionagent.semantic.dto.EvidenceIdentity;
import com.java.system.sessionagent.semantic.dto.ProviderDtos;
import com.java.system.sessionagent.semantic.dto.InternalReferenceTarget;
import com.java.system.sessionagent.semantic.dto.MethodTarget;
import com.java.system.sessionagent.semantic.dto.SemanticIdentity;
import com.java.system.sessionagent.semantic.dto.SemanticLocation;
import com.java.system.sessionagent.semantic.tool.input.ConceptKind;
import com.java.system.sessionagent.semantic.tool.input.ConceptTerm;
import com.java.system.sessionagent.semantic.tool.input.MemberKind;
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
import com.java.system.sessionagent.semantic.tool.input.OutgoingCallGraphInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveConceptInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveSourceSymbolInput;
import com.java.system.sessionagent.semantic.tool.input.SuggestApiRouteInput;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.util.Assert;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class SemanticSourceClient {

    private static final BigInteger MAX_RETRY_AFTER_SECONDS = BigInteger.valueOf(60);
    private static final JsonMapper RESPONSE_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private final RestClient restClient;
    private final SemanticRepositoryClient repositoryClient;

    public SemanticSourceClient(RestClient restClient, SemanticRepositoryClient repositoryClient) {
        this.restClient = strictResponseClient(Objects.requireNonNull(restClient, "Semantic service RestClient must not be null"));
        this.repositoryClient = Objects.requireNonNull(repositoryClient, "Semantic repository client must not be null");
    }

    private static RestClient strictResponseClient(RestClient restClient) {
        return restClient.mutate().messageConverters(converters -> {
            converters.removeIf(SemanticSourceClient::isJacksonConverter);
            converters.add(new JacksonJsonHttpMessageConverter(RESPONSE_MAPPER));
        }).build();
    }

    private static boolean isJacksonConverter(HttpMessageConverter<?> converter) {
        return converter instanceof JacksonJsonHttpMessageConverter;
    }

    public SourceResult<ProviderDtos.EntryPointsResponse> listEntryPoints(ListEntryPointsInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        try {
            ProviderDtos.EntryPointsResponse response = restClient.get().uri(builder -> builder
                    .path("/v1/repositories/{repositoryId}/entry-points")
                    .queryParam("expectedRevision", revision.value())
                    .queryParamIfPresent("types", Optional.ofNullable(input.type()).map(Enum::name))
                    .build(input.repositoryId())).retrieve().body(ProviderDtos.EntryPointsResponse.class);
            return valid(revision, response, scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()));
        } catch (SemanticFailure exception) { throw exception; }
        catch (RestClientResponseException exception) { throw classify(exception, input.repositoryId(), revision); }
        catch (ResourceAccessException exception) { throw SemanticFailure.transientFailure(Optional.empty(), exception); }
        catch (RestClientException exception) { throw SemanticHttpFailures.classify(exception); }
        catch (RuntimeException exception) { throw SemanticFailure.invalidResponse(); }
    }

    public SourceResult<ProviderDtos.ApiRouteCandidatesResponse> lookupApiRoute(LookupApiRouteInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return routes("/v1/api-routes/lookup", input.repositoryId(), new LookupRequest(input.apiPath(), input.httpMethod(), input.repositoryId(), revision.value()), revision, ProviderDtos.ApiRouteCandidatesResponse.class, ProviderDtos.ApiRouteCandidatesResponse::candidates);
    }

    public SourceResult<ProviderDtos.ApiRouteCandidatesResponse> suggestApiRoute(SuggestApiRouteInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return routes("/v1/api-routes/suggest", input.repositoryId(), new SuggestRequest(input.apiPath(), input.httpMethod(), input.repositoryId(), revision.value(), input.limit()), revision, ProviderDtos.ApiRouteCandidatesResponse.class, ProviderDtos.ApiRouteCandidatesResponse::candidates);
    }

    public SourceResult<ProviderDtos.OutgoingCallGraphResponse> outgoingCallGraph(OutgoingCallGraphInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return post("/v1/analyses/call-graphs/outgoing", input.repositoryId(), revision, new CallGraphRequest(input.repositoryId(), revision.value(), input.depth(), input.target()), ProviderDtos.OutgoingCallGraphResponse.class,
                response -> matchesRevision(revision, response.analyzedRevision())
                        && hasGraphRoot(response.rootNodeId(), response.nodes(), input.target()));
    }

    public SourceResult<ProviderDtos.IncomingCallGraphResponse> incomingCallGraph(IncomingCallGraphInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return post("/v1/analyses/call-graphs/incoming", input.repositoryId(), revision, new CallGraphRequest(input.repositoryId(), revision.value(), input.depth(), input.target()), ProviderDtos.IncomingCallGraphResponse.class,
                response -> matchesRevision(revision, response.analyzedRevision())
                        && hasGraphRoot(response.rootNodeId(), response.nodes(), input.target()));
    }

    public SourceResult<ProviderDtos.DiscoverConceptsResponse> discoverConcepts(DiscoverConceptsInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return post("/v1/discovery/concepts", input.repositoryId(), revision, new ConceptsRequest(input.repositoryId(), revision.value(), input.terms(), input.kinds(), "ALL", input.packagePrefix(), input.offset(), input.limit()), ProviderDtos.DiscoverConceptsResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()));
    }
    public SourceResult<ProviderDtos.ResolveConceptResponse> resolveConcept(ResolveConceptInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        ProviderDtos.ConceptFollowUpIdentity identity = conceptIdentity(input.identity());
        return post("/v1/discovery/concepts/resolve", input.repositoryId(), revision,
                new ProviderDtos.IdentityFollowUpRequest(input.repositoryId(), revision.value(), identity),
                ProviderDtos.ResolveConceptResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision())
                        && identity.equals(response.candidate().identity()));
    }
    public SourceResult<ProviderDtos.DiscoverEventListenersResponse> discoverEventListeners(DiscoverEventListenersInput input) { RepositoryRevision revision = revision(input.repositoryId()); return post("/v1/discovery/event-listeners", input.repositoryId(), revision, new EventListenersRequest(input.repositoryId(), revision.value(), input.eventType(), input.offset(), input.limit()), ProviderDtos.DiscoverEventListenersResponse.class, response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()) && input.eventType().equals(response.requestedEventType())); }
    public SourceResult<ProviderDtos.DiscoverMethodImplementationsResponse> discoverMethodImplementations(DiscoverMethodImplementationsInput input) { RepositoryRevision revision = revision(input.repositoryId()); return post("/v1/discovery/method-implementations", input.repositoryId(), revision, new MethodImplementationsRequest(input.repositoryId(), revision.value(), input.target()), ProviderDtos.DiscoverMethodImplementationsResponse.class, response -> scoped(input.repositoryId(), revision, response.repoId(), response.revision()) && sameTarget(input.target(), response.requestedTarget())); }
    public SourceResult<ProviderDtos.DiscoverTypeMembersResponse> discoverTypeMembers(DiscoverTypeMembersInput input) { RepositoryRevision revision = revision(input.repositoryId()); return post("/v1/discovery/type-members", input.repositoryId(), revision, new TypeMembersRequest(input.repositoryId(), revision.value(), input.sourceType(), input.memberKinds(), input.namePrefix(), input.offset(), input.limit()), ProviderDtos.DiscoverTypeMembersResponse.class, response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()) && sameSourceType(input.sourceType(), response.sourceType())); }
    public SourceResult<ProviderDtos.FindInternalReferencesResponse> findInternalReferences(FindInternalReferencesInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        ProviderDtos.InternalReferenceFollowUpTarget target = internalReferenceTarget(input.target());
        return post("/v1/discovery/internal-references", input.repositoryId(), revision,
                new ProviderDtos.TargetFollowUpRequest(input.repositoryId(), revision.value(), target,
                        Optional.empty(), Optional.of(input.offset()), Optional.of(input.limit())),
                ProviderDtos.FindInternalReferencesResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision())
                        && target.equals(response.targetDeclaration().target()));
    }

    public SourceResult<ProviderDtos.EvidenceSourceResponse> getEvidenceSource(GetEvidenceSourceInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        ProviderDtos.EvidenceSourceFollowUpIdentity identity = evidenceIdentity(input.identity());
        return post("/v1/discovery/evidence-source", input.repositoryId(), revision,
                new ProviderDtos.IdentityFollowUpRequest(input.repositoryId(), revision.value(), identity),
                ProviderDtos.EvidenceSourceResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision())
                        && identity.equals(response.identity()));
    }
    public SourceResult<ProviderDtos.MethodSourceResponse> getMethodSource(GetMethodSourceInput input) { RepositoryRevision revision = revision(input.repositoryId()); return post("/v1/discovery/method-source", input.repositoryId(), revision, new TargetRequest(input.repositoryId(), revision.value(), input.target()), ProviderDtos.MethodSourceResponse.class, response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()) && input.target().sourceType().sourceFile().equals(response.declarationLocation().sourceFile())); }
    public SourceResult<ProviderDtos.SourceSegmentResponse> getSourceSegment(GetSourceSegmentInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        ProviderDtos.SourceRangePayload location = sourceRange(input.location());
        return post("/v1/discovery/source-segment", input.repositoryId(), revision,
                new ProviderDtos.SourceSegmentFollowUpRequest(input.repositoryId(), revision.value(), location,
                        input.contextLines()),
                ProviderDtos.SourceSegmentResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision())
                        && contains(location, response.segment().location()));
    }

    public SourceResult<ProviderDtos.ResolveSourceSymbolResponse> resolveSourceSymbol(ResolveSourceSymbolInput input) {
        RepositoryRevision revision = revision(input.repositoryId());
        return post("/v1/discovery/source-symbols/resolve", input.repositoryId(), revision,
                new ProviderDtos.ResolveSourceSymbolFollowUpRequest(input.repositoryId(), revision.value(), input.context(),
                        input.symbol(), Optional.ofNullable(input.position())),
                ProviderDtos.ResolveSourceSymbolResponse.class,
                response -> scoped(input.repositoryId(), revision, response.repoId(), response.analyzedRevision()));
    }

    private <R, T> SourceResult<T> post(String path, String repositoryId, RepositoryRevision revision, R request, Class<T> responseType, Predicate<T> responseMatches) {
        try {
            T response = restClient.post().uri(path).body(request).retrieve().body(responseType);
            return valid(revision, response, responseMatches.test(response));
        } catch (SemanticFailure exception) { throw exception; }
        catch (RestClientResponseException exception) { throw classify(exception, repositoryId, revision); }
        catch (ResourceAccessException exception) { throw SemanticFailure.transientFailure(Optional.empty(), exception); }
        catch (RestClientException exception) { throw SemanticHttpFailures.classify(exception); }
        catch (RuntimeException exception) { throw SemanticFailure.invalidResponse(); }
    }

    private <R, T> SourceResult<T> routes(String path, String repositoryId, R request, RepositoryRevision revision, Class<T> responseType, java.util.function.Function<T, List<ProviderDtos.ApiRouteCandidateResponse>> candidates) {
        try {
            T response = restClient.post().uri(path).body(request).retrieve().body(responseType);
            Assert.notNull(response, "Route response must not be null");
            validatePayload(response);
            List<ProviderDtos.ApiRouteCandidateResponse> values = candidates.apply(response);
            Assert.notNull(values, "Route candidates must not be null");
            values.forEach(candidate -> scoped(repositoryId, revision, candidate.repoId(), candidate.analyzedRevision()));
            return new SourceResult<>(revision, response);
        } catch (SemanticFailure exception) { throw exception; }
        catch (RestClientResponseException exception) { throw classify(exception, repositoryId, revision); }
        catch (ResourceAccessException exception) { throw SemanticFailure.transientFailure(Optional.empty(), exception); }
        catch (RestClientException exception) { throw SemanticHttpFailures.classify(exception); }
        catch (RuntimeException exception) { throw SemanticFailure.invalidResponse(); }
    }

    private RepositoryRevision revision(String repositoryId) {
        return repositoryClient.currentRevision(new RepositoryId(repositoryId));
    }

    private static boolean scoped(String repositoryId, RepositoryRevision revision, String responseRepositoryId, String responseRevision) {
        if (!repositoryId.equals(responseRepositoryId)) {
            throw SemanticFailure.invalidResponse();
        }
        return matchesRevision(revision, responseRevision);
    }

    private static boolean matchesRevision(RepositoryRevision revision, String responseRevision) {
        if (!org.springframework.util.StringUtils.hasText(responseRevision)) {
            throw SemanticFailure.invalidResponse();
        }
        if (!revision.value().equals(responseRevision)) {
            throw SemanticFailure.revisionChanged();
        }
        return true;
    }

    private static <T> SourceResult<T> valid(RepositoryRevision revision, T response, boolean matches) {
        Assert.notNull(response, "Semantic source response must not be null");
        validatePayload(response);
        if (!matches) {
            throw SemanticFailure.invalidResponse();
        }
        return new SourceResult<>(revision, response);
    }

    private static void validatePayload(Object payload) {
        if (payload instanceof String value) {
            if (!org.springframework.util.StringUtils.hasText(value)) {
                throw SemanticFailure.invalidResponse();
            }
            return;
        }
        if (payload instanceof List<?> values) {
            values.forEach(SemanticSourceClient::validatePayload);
            return;
        }
        if (payload instanceof Optional<?> optional) {
            optional.ifPresent(SemanticSourceClient::validatePayload);
            return;
        }
        if (!payload.getClass().isRecord() || !payload.getClass().getPackageName().equals(ProviderDtos.class.getPackageName())) {
            return;
        }
        for (RecordComponent component : payload.getClass().getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(payload);
                if (Objects.isNull(value)) {
                    if (component.getType().equals(List.class)
                            || component.getType().isRecord()
                            || component.getName().equals("repoId")
                            || component.getName().equals("analyzedRevision")
                            || component.getName().equals("revision")
                            || component.getName().equals("status")) {
                        throw SemanticFailure.invalidResponse();
                    }
                    continue;
                }
                validatePayload(value);
            } catch (SemanticFailure exception) {
                throw exception;
            } catch (ReflectiveOperationException exception) {
                throw SemanticFailure.invalidResponse();
            }
        }
    }

    private static boolean hasGraphRoot(String rootNodeId, List<ProviderDtos.GraphNode> nodes, MethodTarget target) {
        return nodes.stream().anyMatch(node -> rootNodeId.equals(node.nodeId()) && sameTarget(target, node.target()));
    }

    private static boolean sameTarget(MethodTarget expected, ProviderDtos.MethodTargetPayload actual) {
        return sameSourceType(expected.sourceType(), actual.sourceType())
                && expected.methodName().equals(actual.methodName())
                && expected.parameterTypes().equals(actual.parameterTypes());
    }

    private static boolean sameSourceType(MethodTarget.SourceType expected, ProviderDtos.SourceTypeIdentityPayload actual) {
        return expected.sourceFile().equals(actual.sourceFile())
                && expected.javaType().packageName().equals(actual.javaType().packageName())
                && expected.javaType().className().equals(actual.javaType().className());
    }

    private static ProviderDtos.ConceptFollowUpIdentity conceptIdentity(SemanticIdentity identity) {
        if (identity instanceof SemanticIdentity.Type value) {
            return new ProviderDtos.ConceptFollowUpIdentity("TYPE", Optional.of(value.sourceType()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.Method value) {
            return new ProviderDtos.ConceptFollowUpIdentity("METHOD", Optional.empty(), Optional.of(value.target()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.Field value) {
            return new ProviderDtos.ConceptFollowUpIdentity("FIELD", Optional.empty(), Optional.empty(),
                    Optional.of((ProviderDtos.ConceptIdentityTargetPayload) value.identity()), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.AnnotationUsage value) {
            return new ProviderDtos.ConceptFollowUpIdentity("ANNOTATION_USAGE", Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(value.declaration()), Optional.of(value.annotationType()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.TypeUsage value) {
            return new ProviderDtos.ConceptFollowUpIdentity("TYPE_USAGE", Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(value.owner()), Optional.of(value.location()),
                    Optional.of(value.path()), Optional.of(value.referencedType()), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.ApiRoute value) {
            return new ProviderDtos.ConceptFollowUpIdentity("API_ROUTE", Optional.empty(), Optional.of(value.target()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(value.httpVerb()), Optional.of(value.route()), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.MqDestination value) {
            return new ProviderDtos.ConceptFollowUpIdentity("MQ_DESTINATION", Optional.empty(), Optional.of(value.target()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.broker()), Optional.of(value.destination()),
                    Optional.empty(), Optional.empty());
        }
        if (identity instanceof SemanticIdentity.Schedule value) {
            return new ProviderDtos.ConceptFollowUpIdentity("SCHEDULE", Optional.empty(), Optional.of(value.target()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(value.triggerKind()), value.triggerValue());
        }
        if (identity instanceof SemanticIdentity.MapperStatement value) {
            return new ProviderDtos.ConceptFollowUpIdentity("MAPPER_STATEMENT", Optional.empty(), Optional.empty(),
                    Optional.of(value.identity()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }
        SemanticIdentity.MapperStatementVariant value = (SemanticIdentity.MapperStatementVariant) identity;
        return new ProviderDtos.ConceptFollowUpIdentity("MAPPER_STATEMENT_VARIANT", Optional.empty(), Optional.empty(),
                Optional.of(value.identity()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProviderDtos.InternalReferenceFollowUpTarget internalReferenceTarget(InternalReferenceTarget target) {
        if (target instanceof InternalReferenceTarget.Type value) {
            return new ProviderDtos.InternalReferenceFollowUpTarget("TYPE", value.identity());
        }
        if (target instanceof InternalReferenceTarget.Method value) {
            return new ProviderDtos.InternalReferenceFollowUpTarget("METHOD", value.identity());
        }
        InternalReferenceTarget.Member value = (InternalReferenceTarget.Member) target;
        return new ProviderDtos.InternalReferenceFollowUpTarget("MEMBER", value.identity());
    }

    private static ProviderDtos.EvidenceSourceFollowUpIdentity evidenceIdentity(EvidenceIdentity identity) {
        if (identity instanceof EvidenceIdentity.AnnotationSql value) {
            return new ProviderDtos.EvidenceSourceFollowUpIdentity("ANNOTATION_SQL", Optional.of(value.statementIdentity()),
                    Optional.empty());
        }
        if (identity instanceof EvidenceIdentity.MapperStatement value) {
            return new ProviderDtos.EvidenceSourceFollowUpIdentity("MAPPER_STATEMENT", Optional.of(value.statementIdentity()),
                    Optional.empty());
        }
        EvidenceIdentity.MapperFragment value = (EvidenceIdentity.MapperFragment) identity;
        return new ProviderDtos.EvidenceSourceFollowUpIdentity("MAPPER_FRAGMENT", Optional.empty(),
                Optional.of(value.fragmentIdentity()));
    }

    private static ProviderDtos.SourceRangePayload sourceRange(SemanticLocation location) {
        return new ProviderDtos.SourceRangePayload(location.sourceFile(), new ProviderDtos.TextRangePayload(
                new ProviderDtos.Position(location.range().start().line(), location.range().start().character()),
                new ProviderDtos.Position(location.range().end().line(), location.range().end().character())));
    }

    private static boolean contains(ProviderDtos.SourceRangePayload requested, ProviderDtos.SourceRangePayload response) {
        return requested.sourceFile().equals(response.sourceFile())
                && atOrBefore(response.range().start(), requested.range().start())
                && atOrBefore(requested.range().end(), response.range().end());
    }

    private static boolean atOrBefore(ProviderDtos.Position left, ProviderDtos.Position right) {
        return left.line() < right.line()
                || (left.line().equals(right.line()) && left.character() <= right.character());
    }

    private static SemanticFailure classify(RestClientResponseException exception, String repositoryId, RepositoryRevision revision) {
        HttpStatusCode statusCode = exception.getStatusCode();
        Optional<ProviderDtos.ApiErrorResponse> error = apiError(exception);
        if (statusCode.value() == 404 && error.map(ProviderDtos.ApiErrorResponse::errorCode)
                .filter("REPOSITORY_NOT_FOUND"::equals).isPresent()) {
            return SemanticFailure.unknownRepository();
        }
        if ((statusCode.value() == 404 || statusCode.value() == 422)
                && error.map(ProviderDtos.ApiErrorResponse::errorCode)
                .filter(code -> code.endsWith("_NOT_FOUND")).isPresent()) {
            return SemanticFailure.invalidInput();
        }
        if (statusCode.value() == 400 && error.map(ProviderDtos.ApiErrorResponse::errorCode)
                .filter("REQUEST_INVALID"::equals).isPresent()) {
            return SemanticFailure.invalidInput();
        }
        if (statusCode.value() == 401 || statusCode.value() == 403) { return SemanticFailure.forbidden(); }
        if (statusCode.value() == 429 || statusCode.value() == 503) { return SemanticFailure.transientFailure(retryAfter(exception.getResponseHeaders()), exception); }
        if (statusCode.value() == 409 && isRevisionMismatch(exception, repositoryId, revision)) { return SemanticFailure.revisionChanged(); }
        return SemanticFailure.invalidResponse();
    }

    private static Optional<ProviderDtos.ApiErrorResponse> apiError(RestClientResponseException exception) {
        try {
            ProviderDtos.ApiErrorResponse error = RESPONSE_MAPPER.readValue(
                    exception.getResponseBodyAsString(), ProviderDtos.ApiErrorResponse.class);
            return Optional.of(error).filter(value -> org.springframework.util.StringUtils.hasText(value.errorCode()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static boolean isRevisionMismatch(RestClientResponseException exception, String repositoryId, RepositoryRevision revision) {
        return apiError(exception)
                .filter(error -> "REPOSITORY_REVISION_MISMATCH".equals(error.errorCode()))
                .filter(error -> Objects.isNull(error.repoId()) || repositoryId.equals(error.repoId()))
                .filter(error -> org.springframework.util.StringUtils.hasText(error.expectedRevision()))
                .filter(error -> revision.value().equals(error.expectedRevision()))
                .filter(error -> org.springframework.util.StringUtils.hasText(error.currentRevision()))
                .filter(error -> !revision.value().equals(error.currentRevision()))
                .isPresent();
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return Optional.ofNullable(headers).map(value -> value.getFirst(HttpHeaders.RETRY_AFTER))
                .filter(org.springframework.util.StringUtils::hasText)
                .filter(value -> value.chars().allMatch(character -> character >= '0' && character <= '9'))
                .flatMap(SemanticSourceClient::boundedRetryAfter);
    }

    private static Optional<Duration> boundedRetryAfter(String value) {
        try {
            return Optional.of(Duration.ofSeconds(new BigInteger(value).min(MAX_RETRY_AFTER_SECONDS).longValueExact()));
        } catch (NumberFormatException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    public record SourceResult<T>(RepositoryRevision revision, T response) {
        public SourceResult { Assert.notNull(revision, "Repository revision must not be null"); Assert.notNull(response, "Semantic source response must not be null"); }
    }

    private record LookupRequest(String apiPath, String httpMethod, String repoId, String expectedRevision) { }
    private record SuggestRequest(String apiPath, String httpMethod, String repoId, String expectedRevision, int limit) { }
    private record CallGraphRequest(String repoId, String expectedRevision, int depth, MethodTarget target) { }
    private record ConceptsRequest(String repoId, String expectedRevision, List<ConceptTerm> terms, List<ConceptKind> kinds, String operator, String packagePrefix, int offset, int limit) { }
    private record EventListenersRequest(String repoId, String expectedRevision, String eventType, int offset, int limit) { }
    private record MethodImplementationsRequest(String repoId, String expectedRevision, MethodTarget declarationTarget) { }
    private record TypeMembersRequest(String repoId, String expectedRevision, MethodTarget.SourceType sourceType, List<MemberKind> memberKinds, String namePrefix, int offset, int limit) { }
    private record TargetRequest(String repoId, String expectedRevision, MethodTarget target) { }
}
