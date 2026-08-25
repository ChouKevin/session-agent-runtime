package com.java.system.sessionagent.semantic.tool;

import com.java.system.sessionagent.semantic.RepositoryCatalog;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
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
import com.java.system.sessionagent.semantic.tool.input.GetCodeFactInput;
import com.java.system.sessionagent.semantic.tool.input.SearchCodeFactsInput;
import com.java.system.sessionagent.semantic.tool.input.ResolveSourceSymbolInput;
import com.java.system.sessionagent.semantic.tool.input.SuggestApiRouteInput;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class SemanticToolProvider {

    private static final ToolName LIST_REPOSITORIES = new ToolName("list_repositories");
    private static final String VERSION = "v1";

    private final RepositoryCatalog repositoryCatalog;
    private final Optional<SemanticSourceClient> sourceClient;
    private final ToolSchemaFactory schemaFactory;
    private final StrictJsonCodec jsonCodec;

    public SemanticToolProvider(RepositoryCatalog repositoryCatalog) {
        this(repositoryCatalog, Optional.empty(), new ToolSchemaFactory(), new StrictJsonCodec());
    }

    public SemanticToolProvider(RepositoryCatalog repositoryCatalog, SemanticSourceClient sourceClient) {
        this(repositoryCatalog, Optional.of(sourceClient), new ToolSchemaFactory(), new StrictJsonCodec());
    }

    SemanticToolProvider(
            RepositoryCatalog repositoryCatalog, Optional<SemanticSourceClient> sourceClient,
            ToolSchemaFactory schemaFactory,
            StrictJsonCodec jsonCodec) {
        Assert.notNull(repositoryCatalog, "Repository catalog must not be null");
        Assert.notNull(schemaFactory, "Tool schema factory must not be null");
        Assert.notNull(jsonCodec, "JSON codec must not be null");
        Assert.notNull(sourceClient, "Semantic source client must not be null");
        this.repositoryCatalog = repositoryCatalog;
        this.sourceClient = sourceClient;
        this.schemaFactory = schemaFactory;
        this.jsonCodec = jsonCodec;
    }

    public List<ToolRegistration<?>> registrations() {
        ToolDefinition definition = new ToolDefinition(
                LIST_REPOSITORIES,
                VERSION,
                "List repositories available from Semantic Service",
                schemaFactory.schemaFor(ListRepositoriesInput.class),
                ToolKind.CATALOG);
        ToolRegistration<ListRepositoriesInput> registration = new ToolRegistration<>(
                definition,
                ListRepositoriesInput.class,
                input -> executeCatalog());
        return sourceClient.map(client -> sourceRegistrations(registration, client)).orElseGet(() -> List.of(registration));
    }

    private ToolResult listRepositories() {
        List<RepositorySummary> repositories = repositoryCatalog.listRepositories();
        String dataJson = jsonCodec.canonicalize(ListRepositoriesResult.from(repositories));
        return new ToolResult(Optional.empty(), Optional.empty(), dataJson, false);
    }

    private ToolResult executeCatalog() {
        try {
            return listRepositories();
        } catch (com.java.system.sessionagent.semantic.SemanticFailure exception) {
            throw translate(exception);
        }
    }

    private List<ToolRegistration<?>> sourceRegistrations(ToolRegistration<ListRepositoriesInput> catalog, SemanticSourceClient client) {
        return List.of(catalog,
                source(SemanticToolName.LIST_ENTRY_POINTS,
                        "List API, MQ, and scheduled entry points for repositoryId at revision.",
                        ListEntryPointsInput.class, ListEntryPointsInput::repositoryId, client::listEntryPoints),
                source(SemanticToolName.LOOKUP_API_ROUTE,
                        "Look up the exact httpMethod and path for repositoryId at revision.",
                        LookupApiRouteInput.class, LookupApiRouteInput::repositoryId, client::lookupApiRoute),
                source(SemanticToolName.SUGGEST_API_ROUTE,
                        "Suggest API routes near the supplied httpMethod and path for repositoryId at revision.",
                        SuggestApiRouteInput.class, SuggestApiRouteInput::repositoryId, client::suggestApiRoute),
                source(SemanticToolName.OUTGOING_CALL_GRAPH,
                        "Find callees from the flat packageName, className, sourceFile, methodName, and parameterTypes at depth 1 or 2.",
                        OutgoingCallGraphInput.class, OutgoingCallGraphInput::repositoryId, client::outgoingCallGraph),
                source(SemanticToolName.INCOMING_CALL_GRAPH,
                        "Find callers of the flat packageName, className, sourceFile, methodName, and parameterTypes at depth 1 or 2.",
                        IncomingCallGraphInput.class, IncomingCallGraphInput::repositoryId, client::incomingCallGraph),
                source(SemanticToolName.SEARCH_CODE_FACTS,
                        "Search code-derived facts. Copy repositoryId and revision from prior evidence; omit unknown optional filters rather than guessing.",
                        SearchCodeFactsInput.class, SearchCodeFactsInput::repositoryId, client::searchCodeFacts),
                source(SemanticToolName.GET_CODE_FACT,
                        "Get one exact factId copied from a prior code-fact search, using the same repositoryId and revision from prior evidence.",
                        GetCodeFactInput.class, GetCodeFactInput::repositoryId, client::getCodeFact),
                source(SemanticToolName.DISCOVER_EVENT_LISTENERS,
                        "Discover listeners for a fully qualified Java event type, for example com.example.order.OrderCancelledEvent.",
                        DiscoverEventListenersInput.class, DiscoverEventListenersInput::repositoryId, client::discoverEventListeners),
                source(SemanticToolName.DISCOVER_METHOD_IMPLEMENTATIONS,
                        "Discover source-defined implementations of the supplied flat Java method fields.",
                        DiscoverMethodImplementationsInput.class, DiscoverMethodImplementationsInput::repositoryId, client::discoverMethodImplementations),
                source(SemanticToolName.DISCOVER_TYPE_MEMBERS,
                        "Discover selected kinds in the Java type identified by packageName, className, and sourceFile.",
                        DiscoverTypeMembersInput.class, DiscoverTypeMembersInput::repositoryId, client::discoverTypeMembers),
                source(SemanticToolName.FIND_INTERNAL_REFERENCES,
                        "Find internal references to the Java method identified by the supplied flat method fields.",
                        FindInternalReferencesInput.class, FindInternalReferencesInput::repositoryId, client::findInternalReferences),
                source(SemanticToolName.GET_EVIDENCE_SOURCE,
                        "Get source evidence for the Java method identified by the supplied flat method fields.",
                        GetEvidenceSourceInput.class, GetEvidenceSourceInput::repositoryId, client::getEvidenceSource),
                source(SemanticToolName.GET_METHOD_SOURCE,
                        "Get source for the Java method identified by the supplied flat method fields.",
                        GetMethodSourceInput.class, GetMethodSourceInput::repositoryId, client::getMethodSource),
                source(SemanticToolName.GET_SOURCE_SEGMENT,
                        "Get source text for the exact sourceFile and zero-based start/end positions copied from a prior result.",
                        GetSourceSegmentInput.class, GetSourceSegmentInput::repositoryId, client::getSourceSegment),
                source(SemanticToolName.RESOLVE_SOURCE_SYMBOL,
                        "Resolve a symbol from exact source context and an optional zero-based position.",
                        ResolveSourceSymbolInput.class, ResolveSourceSymbolInput::repositoryId, client::resolveSourceSymbol));
    }

    private <I, T> ToolRegistration<I> source(
            SemanticToolName name,
            String description,
            Class<I> inputType,
            Function<I, String> repositoryId,
            Function<I, SemanticSourceClient.SourceResult<T>> executor) {
        ToolDefinition definition = new ToolDefinition(name.toolName(), VERSION, description,
                schemaFactory.schemaFor(inputType), ToolKind.SOURCE);
        return new ToolRegistration<>(definition, inputType, input -> executeSource(repositoryId.apply(input), executor, input));
    }

    private <I, T> ToolResult executeSource(String repositoryId, Function<I, SemanticSourceClient.SourceResult<T>> executor, I input) {
        try {
            return sourceResult(repositoryId, executor.apply(input));
        } catch (com.java.system.sessionagent.semantic.SemanticFailure exception) {
            throw translate(exception);
        }
    }

    private static ToolExecutionFailure translate(com.java.system.sessionagent.semantic.SemanticFailure failure) {
        return switch (failure.kind()) {
            case INVALID_ARGUMENT -> ToolExecutionFailure.invalidInput();
            case REPOSITORY_NOT_FOUND -> ToolExecutionFailure.repositoryNotFound();
            case REVISION_OUTDATED -> {
                com.java.system.sessionagent.semantic.SemanticFailure.RevisionOutdatedDetails details = failure.revisionOutdated().orElseThrow();
                yield ToolExecutionFailure.revisionOutdated(details.repositoryId(), details.requestedRevision(), details.currentRevision(), details.retryGuidance());
            }
            case INDEX_NOT_READY -> ToolExecutionFailure.indexNotReady();
            case INDEX_CONTRACT_MISMATCH -> ToolExecutionFailure.indexContractMismatch();
            case CODE_FACT_NOT_FOUND -> ToolExecutionFailure.codeFactNotFound();
            case CODE_FACT_KIND_UNSUPPORTED -> ToolExecutionFailure.codeFactKindUnsupported();
            case INVALID_QUERY -> ToolExecutionFailure.invalidQuery();
            case SEMANTIC_INDEX_UNAVAILABLE -> ToolExecutionFailure.semanticIndexUnavailable(failure.retryAfter());
            case FORBIDDEN -> ToolExecutionFailure.forbidden();
            case INVALID_RESPONSE -> ToolExecutionFailure.invalidResponse();
        };
    }

    private <T> ToolResult sourceResult(String repositoryId, SemanticSourceClient.SourceResult<T> sourceResult) {
        return new ToolResult(Optional.of(repositoryId), Optional.of(sourceResult.revision().value()),
                jsonCodec.canonicalize(sourceResult.response()), true);
    }

    private record ListRepositoriesResult(List<RepositorySummaryResult> repositories) {

        private static ListRepositoriesResult from(List<RepositorySummary> repositories) {
            return new ListRepositoriesResult(repositories.stream()
                    .map(repository -> new RepositorySummaryResult(repository.repositoryId().value(), repository.revision().value()))
                    .toList());
        }
    }

    private record RepositorySummaryResult(String repositoryId, String revision) {
    }
}
