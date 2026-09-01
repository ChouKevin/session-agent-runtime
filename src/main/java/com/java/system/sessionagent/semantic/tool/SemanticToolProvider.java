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
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class SemanticToolProvider {

    private static final ToolName LIST_REPOSITORIES = new ToolName("list_repositories");

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
                "List available repositories and their current repositoryId/revision pairs. "
                        + "Use when a repository-specific query is useful and visible history has no reliable "
                        + "repositoryId/revision pair. The catalog identifies repositories; it does not describe source behavior.",
                schemaFactory.schemaFor(ListRepositoriesInput.class));
        ToolRegistration<ListRepositoriesInput> registration = new ToolRegistration<>(
                definition,
                ListRepositoriesInput.class,
                input -> executeCatalog());
        return sourceClient.map(client -> sourceRegistrations(registration, client)).orElseGet(() -> List.of(registration));
    }

    private String listRepositories() {
        List<RepositorySummary> repositories = repositoryCatalog.listRepositories();
        String dataJson = jsonCodec.canonicalize(ListRepositoriesResult.from(repositories));
        return dataJson;
    }

    private String executeCatalog() {
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
                        "Search code-derived facts. Copy repositoryId and revision from prior evidence; omit unknown optional filters rather than guessing. "
                                + "Returned facts are evidence, so do not call another tool merely to confirm details already present. "
                                + "For enum values, narrow the search with kinds=[ENUM_CONSTANT]. An empty result supports a codebase-limited absence finding "
                                + "only when totalCount is 0, hasMore is false, and coverage.issues is empty. Once those fields show a complete empty result, "
                                + "do not retry alternate spellings or inspect unrelated details solely to reconfirm that repository-limited absence.",
                        SearchCodeFactsInput.class, SearchCodeFactsInput::repositoryId, client::searchCodeFacts),
                source(SemanticToolName.GET_CODE_FACT,
                        "Get one exact opaque factId copied from a prior code-fact search, using the same repositoryId and revision from prior evidence. "
                                + "A factId is not a class or method name. Use this tool only for needed detail absent from the search result.",
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
                        "Get source for the Java method identified by the supplied flat method fields. If the source shows a value comes from runtime, "
                                + "configuration, database, or external service data, report that limit rather than searching Semantic for a live value.",
                        GetMethodSourceInput.class, GetMethodSourceInput::repositoryId, client::getMethodSource),
                source(SemanticToolName.GET_SOURCE_SEGMENT,
                        "Get source text only for the exact sourceFile and zero-based start/end positions copied from one prior result. "
                                + "Never invent or expand a range. This tool is not needed to reconfirm facts or source already visible.",
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
        ToolDefinition definition = new ToolDefinition(name.toolName(), description, schemaFactory.schemaFor(inputType));
        return new ToolRegistration<>(definition, inputType, input -> executeSource(repositoryId.apply(input), executor, input));
    }

    private <I, T> String executeSource(String repositoryId, Function<I, SemanticSourceClient.SourceResult<T>> executor, I input) {
        try {
            return sourceResult(repositoryId, executor.apply(input));
        } catch (com.java.system.sessionagent.semantic.SemanticFailure exception) {
            throw translate(exception);
        }
    }

    private static ToolExecutionFailure translate(com.java.system.sessionagent.semantic.SemanticFailure failure) {
        return switch (failure.kind()) {
            case INVALID_ARGUMENT -> new ToolExecutionFailure("TOOL_INPUT_INVALID", "The tool input is invalid.");
            case REPOSITORY_NOT_FOUND -> new ToolExecutionFailure("SEMANTIC_REPOSITORY_NOT_FOUND", "The requested repository was not found.");
            case REVISION_OUTDATED -> new ToolExecutionFailure("SEMANTIC_REVISION_OUTDATED", "The requested repository revision is outdated.");
            case INDEX_NOT_READY -> new ToolExecutionFailure("SEMANTIC_INDEX_NOT_READY", "The semantic index is not ready.");
            case INDEX_CONTRACT_MISMATCH -> new ToolExecutionFailure("SEMANTIC_INDEX_CONTRACT_MISMATCH", "The semantic index contract does not match.");
            case CODE_FACT_NOT_FOUND -> new ToolExecutionFailure("SEMANTIC_CODE_FACT_NOT_FOUND", "The requested code fact was not found.");
            case CODE_FACT_KIND_UNSUPPORTED -> new ToolExecutionFailure("SEMANTIC_CODE_FACT_KIND_UNSUPPORTED", "The code fact kind is unsupported.");
            case INVALID_QUERY -> new ToolExecutionFailure("SEMANTIC_QUERY_INVALID", "The semantic query is invalid.");
            case SEMANTIC_INDEX_UNAVAILABLE -> new ToolExecutionFailure("SEMANTIC_INDEX_UNAVAILABLE", "The semantic index is unavailable.");
            case FORBIDDEN -> new ToolExecutionFailure("SEMANTIC_FORBIDDEN", "Semantic access is forbidden.");
            case INVALID_RESPONSE -> new ToolExecutionFailure("SEMANTIC_RESPONSE_INVALID", "The semantic response is invalid.");
        };
    }

    private <T> String sourceResult(String repositoryId, SemanticSourceClient.SourceResult<T> sourceResult) {
        return jsonCodec.canonicalize(new SourceObservation(repositoryId, sourceResult.revision().value(), sourceResult.response()));
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

    private record SourceObservation(String repositoryId, String revision, Object data) {
    }
}
