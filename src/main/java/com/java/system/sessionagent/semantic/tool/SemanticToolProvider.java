package com.java.system.sessionagent.semantic.tool;

import com.java.system.sessionagent.semantic.RepositoryCatalog;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.json.SemanticResultJsonWriter;
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
    private final SemanticResultJsonWriter resultWriter;

    public SemanticToolProvider(RepositoryCatalog repositoryCatalog) {
        this(repositoryCatalog, Optional.empty(), new ToolSchemaFactory(), new StrictJsonCodec(), new SemanticResultJsonWriter());
    }

    public SemanticToolProvider(RepositoryCatalog repositoryCatalog, SemanticSourceClient sourceClient) {
        this(repositoryCatalog, Optional.of(sourceClient), new ToolSchemaFactory(), new StrictJsonCodec(), new SemanticResultJsonWriter());
    }

    SemanticToolProvider(
            RepositoryCatalog repositoryCatalog, Optional<SemanticSourceClient> sourceClient,
            ToolSchemaFactory schemaFactory,
            StrictJsonCodec jsonCodec, SemanticResultJsonWriter resultWriter) {
        Assert.notNull(repositoryCatalog, "Repository catalog must not be null");
        Assert.notNull(schemaFactory, "Tool schema factory must not be null");
        Assert.notNull(jsonCodec, "JSON codec must not be null");
        Assert.notNull(sourceClient, "Semantic source client must not be null");
        Assert.notNull(resultWriter, "Semantic result writer must not be null");
        this.repositoryCatalog = repositoryCatalog;
        this.sourceClient = sourceClient;
        this.schemaFactory = schemaFactory;
        this.jsonCodec = jsonCodec;
        this.resultWriter = resultWriter;
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
                source(SemanticToolName.LIST_ENTRY_POINTS, "List codebase entry points", ListEntryPointsInput.class, ListEntryPointsInput::repositoryId, client::listEntryPoints),
                source(SemanticToolName.LOOKUP_API_ROUTE, "Look up an API route", LookupApiRouteInput.class, LookupApiRouteInput::repositoryId, client::lookupApiRoute),
                source(SemanticToolName.SUGGEST_API_ROUTE, "Suggest API routes", SuggestApiRouteInput.class, SuggestApiRouteInput::repositoryId, client::suggestApiRoute),
                source(SemanticToolName.OUTGOING_CALL_GRAPH, "Read outgoing call graph", OutgoingCallGraphInput.class, OutgoingCallGraphInput::repositoryId, client::outgoingCallGraph),
                source(SemanticToolName.INCOMING_CALL_GRAPH, "Read incoming call graph", IncomingCallGraphInput.class, IncomingCallGraphInput::repositoryId, client::incomingCallGraph),
                source(SemanticToolName.DISCOVER_CONCEPTS, "Discover codebase concepts", DiscoverConceptsInput.class, DiscoverConceptsInput::repositoryId, client::discoverConcepts),
                source(SemanticToolName.RESOLVE_CONCEPT, "Resolve a codebase concept", ResolveConceptInput.class, ResolveConceptInput::repositoryId, client::resolveConcept),
                source(SemanticToolName.DISCOVER_EVENT_LISTENERS, "Discover event listeners", DiscoverEventListenersInput.class, DiscoverEventListenersInput::repositoryId, client::discoverEventListeners),
                source(SemanticToolName.DISCOVER_METHOD_IMPLEMENTATIONS, "Discover method implementations", DiscoverMethodImplementationsInput.class, DiscoverMethodImplementationsInput::repositoryId, client::discoverMethodImplementations),
                source(SemanticToolName.DISCOVER_TYPE_MEMBERS, "Discover type members", DiscoverTypeMembersInput.class, DiscoverTypeMembersInput::repositoryId, client::discoverTypeMembers),
                source(SemanticToolName.FIND_INTERNAL_REFERENCES, "Find internal references", FindInternalReferencesInput.class, FindInternalReferencesInput::repositoryId, client::findInternalReferences),
                source(SemanticToolName.GET_EVIDENCE_SOURCE, "Get evidence source", GetEvidenceSourceInput.class, GetEvidenceSourceInput::repositoryId, client::getEvidenceSource),
                source(SemanticToolName.GET_METHOD_SOURCE, "Get method source", GetMethodSourceInput.class, GetMethodSourceInput::repositoryId, client::getMethodSource),
                source(SemanticToolName.GET_SOURCE_SEGMENT, "Get a source segment", GetSourceSegmentInput.class, GetSourceSegmentInput::repositoryId, client::getSourceSegment),
                source(SemanticToolName.RESOLVE_SOURCE_SYMBOL, "Resolve a source symbol", ResolveSourceSymbolInput.class, ResolveSourceSymbolInput::repositoryId, client::resolveSourceSymbol));
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
            case UNKNOWN_REPOSITORY -> ToolExecutionFailure.unknownRepository();
            case REVISION_CHANGED -> ToolExecutionFailure.revisionChanged();
            case TRANSIENT -> ToolExecutionFailure.transientFailure(failure.retryAfter());
            case FORBIDDEN -> ToolExecutionFailure.forbidden();
            case INVALID_RESPONSE -> ToolExecutionFailure.invalidResponse();
        };
    }

    private <T> ToolResult sourceResult(String repositoryId, SemanticSourceClient.SourceResult<T> sourceResult) {
        return new ToolResult(Optional.of(repositoryId), Optional.of(sourceResult.revision().value()),
                resultWriter.write(sourceResult.response()), true);
    }

    private record ListRepositoriesResult(List<RepositorySummaryResult> repositories) {

        private static ListRepositoriesResult from(List<RepositorySummary> repositories) {
            return new ListRepositoriesResult(repositories.stream()
                    .map(repository -> new RepositorySummaryResult(repository.repositoryId().value(), repository.displayName()))
                    .toList());
        }
    }

    private record RepositorySummaryResult(String repositoryId, String displayName) {
    }
}
