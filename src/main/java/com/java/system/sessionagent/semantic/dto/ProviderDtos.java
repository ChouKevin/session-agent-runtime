package com.java.system.sessionagent.semantic.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Java Semantic Service v1 HTTP 文件使用的窄 DTO 集合
 */
public final class ProviderDtos {

    private ProviderDtos() {
    }

    public record RepositoryStatusResponse(String repoId, String mode, String displayName,
                                           String currentBranch, String currentRevision, Boolean cloned) {
        public RepositoryStatusResponse {
            repoId = requiredText(repoId, "repoId");
            mode = requiredText(mode, "mode");
            displayName = requiredText(displayName, "displayName");
            currentBranch = requiredText(currentBranch, "currentBranch");
            currentRevision = requiredText(currentRevision, "currentRevision");
            cloned = Objects.requireNonNull(cloned, "cloned is required");
        }
    }

    public record EntryPointsResponse(String repoId, String analyzedRevision,
                                      List<EntryPointClassResponse> entryPoints) {
        public EntryPointsResponse {
            repoId = requiredText(repoId, "repoId");
            analyzedRevision = requiredText(analyzedRevision, "analyzedRevision");
            entryPoints = List.copyOf(Objects.requireNonNull(entryPoints, "entryPoints are required"));
        }
    }

    public record EntryPointClassResponse(String className, String packageName, String packagePath,
                                          String description, List<String> basePaths,
                                          List<EntryPointMethodResponse> methods) {
        public EntryPointClassResponse {
            className = requiredText(className, "className");
            packageName = requiredText(packageName, "packageName");
            packagePath = requiredText(packagePath, "packagePath");
            description = requiredText(description, "description");
            basePaths = List.copyOf(Objects.requireNonNull(basePaths, "basePaths are required"));
            methods = List.copyOf(Objects.requireNonNull(methods, "methods are required"));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ApiEntryPointMethodResponse.class, name = "API"),
            @JsonSubTypes.Type(value = MqEntryPointMethodResponse.class, name = "MQ"),
            @JsonSubTypes.Type(value = ScheduleEntryPointMethodResponse.class, name = "SCHEDULE")
    })
    public sealed interface EntryPointMethodResponse permits ApiEntryPointMethodResponse,
            MqEntryPointMethodResponse, ScheduleEntryPointMethodResponse {

        String name();

        String description();

        String type();

        MethodTargetResolutionResponse analysisTarget();
    }

    public record ApiEntryPointMethodResponse(String name, String description, String type, String apiUrl,
                                              List<String> httpMethods, List<String> swaggerDescriptions,
                                              MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
        public ApiEntryPointMethodResponse {
            name = requiredText(name, "name");
            description = requiredText(description, "description");
            type = requiredKind(type, "API");
            apiUrl = requiredText(apiUrl, "apiUrl");
            httpMethods = List.copyOf(Objects.requireNonNull(httpMethods, "httpMethods are required"));
            swaggerDescriptions = List.copyOf(Objects.requireNonNull(swaggerDescriptions, "swaggerDescriptions are required"));
            analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        }
    }

    public record MqEntryPointMethodResponse(String name, String description, String type, String broker,
                                             List<String> destinations,
                                             MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
        public MqEntryPointMethodResponse {
            name = requiredText(name, "name");
            description = requiredText(description, "description");
            type = requiredKind(type, "MQ");
            broker = requiredText(broker, "broker");
            destinations = List.copyOf(Objects.requireNonNull(destinations, "destinations are required"));
            analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        }
    }

    public record ScheduleEntryPointMethodResponse(String name, String description, String type,
                                                   String triggerKind, String triggerValue,
                                                   MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
        public ScheduleEntryPointMethodResponse {
            name = requiredText(name, "name");
            description = requiredText(description, "description");
            type = requiredKind(type, "SCHEDULE");
            triggerKind = requiredText(triggerKind, "triggerKind");
            triggerValue = requiredText(triggerValue, "triggerValue");
            analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        }
    }

    public record ApiRouteLookupRequest(String apiPath, String httpMethod, String repoId, String expectedRevision) {
    }

    public record ApiRouteSuggestRequest(String apiPath, String httpMethod, String repoId, String expectedRevision,
                                         Integer limit) {
    }

    public record ApiRouteCandidatesResponse(List<ApiRouteCandidateResponse> candidates,
                                             List<ApiRouteObservationResponse> observations) {
        public ApiRouteCandidatesResponse {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            observations = List.copyOf(Objects.requireNonNull(observations, "observations are required"));
        }
    }

    public record ApiRouteCandidateResponse(String repoId, String analyzedRevision, String httpMethod,
                                            String routeTemplate, String packageName, String className,
                                            String methodName, MethodTargetResolutionResponse analysisTarget,
                                            List<String> matchReasons) {
        public ApiRouteCandidateResponse {
            repoId = requiredText(repoId, "repoId");
            analyzedRevision = requiredText(analyzedRevision, "analyzedRevision");
            httpMethod = requiredText(httpMethod, "httpMethod");
            routeTemplate = requiredText(routeTemplate, "routeTemplate");
            packageName = requiredText(packageName, "packageName");
            className = requiredText(className, "className");
            methodName = requiredText(methodName, "methodName");
            analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
            matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
        }
    }

    public record ApiRouteObservationResponse(String code, String description) {
    }

    public record AnalyzeOutgoingCallGraphRequest(String repoId, String expectedRevision, Integer depth,
                                                  MethodTargetPayload target) {
    }

    public record AnalyzeIncomingCallGraphRequest(String repoId, String expectedRevision, Integer depth,
                                                  MethodTargetPayload target) {
    }

    public record MethodTargetResolutionResponse(String status, MethodTargetPayload target,
                                                 List<MethodTargetPayload> candidates, String reasonCode) {
    }

    public record OutgoingCallGraphResponse(String status, String analyzedRevision, String rootNodeId,
                                            GraphTraversal traversal, List<GraphNode> nodes,
                                            List<GraphEdge> edges, List<GraphWarning> warnings,
                                            List<GraphError> errors) {
    }

    public record IncomingCallGraphResponse(String status, String analyzedRevision, String rootNodeId,
                                            GraphTraversal traversal, List<GraphNode> nodes,
                                            List<GraphEdge> edges, List<GraphWarning> warnings,
                                            List<GraphError> errors) {
    }

    public record GraphTraversal(Integer requestedDepth, Integer expandedNodeCount, Integer nodeBudget,
                                 Boolean rootDirectCallsComplete, String limitReason) {
        public GraphTraversal {
            requestedDepth = requiredNonNegative(requestedDepth, "requestedDepth");
            expandedNodeCount = requiredNonNegative(expandedNodeCount, "expandedNodeCount");
            nodeBudget = requiredNonNegative(nodeBudget, "nodeBudget");
            rootDirectCallsComplete = Objects.requireNonNull(rootDirectCallsComplete, "rootDirectCallsComplete is required");
        }
    }

    public record GraphNode(String nodeId, MethodTargetPayload target, String externalSymbol, String contentState,
                            String traversalState, String dispatchKind, TextRangePayload declarationRange,
                            List<AvailableFollowUp> availableFollowUps) {

        public GraphNode {
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphEdge(String callerNodeId, String calleeNodeId, SourceRangePayload callSite,
                            String callExpression, String resolutionStrategy, String category,
                            List<String> evidence, List<AvailableFollowUp> availableFollowUps) {

        public GraphEdge {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }

        public GraphEdge(String callerNodeId, String calleeNodeId, SourceRangePayload callSite,
                         String callExpression, String resolutionStrategy, String category,
                         List<String> evidence) {
            this(callerNodeId, calleeNodeId, callSite, callExpression, resolutionStrategy, category,
                    evidence, List.of());
        }
    }

    public record GraphWarning(String code, String message, String nodeId, String callExpression,
                               SourceRangePayload callSite, List<MethodTargetPayload> candidates,
                               List<AvailableFollowUp> availableFollowUps) {

        public GraphWarning {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphError(String code, String message, String nodeId) {
    }

    public record Position(@Min(0) Integer line, @Min(0) Integer character) {
        public Position {
            line = requiredNonNegative(line, "line");
            character = requiredNonNegative(character, "character");
        }
    }

    /** Java 型別的 HTTP 識別資料 */
    public record JavaTypeIdentityPayload(String packageName, String className) {

        public JavaTypeIdentityPayload {
            packageName = requiredText(packageName, "packageName");
            className = requiredText(className, "className");
        }
    }

    /** 以來源檔案限定的 Java 型別 HTTP 識別資料 */
    public record SourceTypeIdentityPayload(JavaTypeIdentityPayload javaType, String sourceFile)
            implements InternalReferenceIdentity {

        public SourceTypeIdentityPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = requiredText(sourceFile, "sourceFile");
        }
    }

    /** 正規方法目標 HTTP 資料 */
    public record MethodTargetPayload(SourceTypeIdentityPayload sourceType, String methodName,
                                      List<String> parameterTypes) implements FollowUpTarget, InternalReferenceIdentity {

        public MethodTargetPayload {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            methodName = requiredText(methodName, "methodName");
            parameterTypes = List.copyOf(Objects.requireNonNull(
                    parameterTypes, "parameterTypes are required"));
        }
    }

    /** 零基 UTF-16 半開文字範圍 HTTP 資料 */
    public record TextRangePayload(Position start, Position end) {

        public TextRangePayload {
            start = Objects.requireNonNull(start, "start is required");
            end = Objects.requireNonNull(end, "end is required");
        }
    }

    /** 型別直接成員或方法範圍成員的封閉 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "scope", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceMemberIdentityPayload.TypeMember.class, name = "TYPE"),
            @JsonSubTypes.Type(value = SourceMemberIdentityPayload.MethodScoped.class, name = "METHOD")})
    public sealed interface SourceMemberIdentityPayload extends InternalReferenceIdentity permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped {

        record TypeMember(String scope, SourceTypeIdentityPayload ownerType, String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public TypeMember {
                scope = requiredKind(scope, "TYPE");
                ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
                name = Objects.requireNonNull(name, "name is required");
            }
        }

        record MethodScoped(String scope, MethodTargetPayload declaringMethod, TextRangePayload declarationRange, String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public MethodScoped {
                scope = requiredKind(scope, "METHOD");
                declaringMethod = Objects.requireNonNull(declaringMethod, "declaringMethod is required");
                declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
                name = Objects.requireNonNull(name, "name is required");
            }
        }
    }

    /** 含有來源檔案的可導覽文字範圍 HTTP 資料 */
    public record SourceRangePayload(String sourceFile, TextRangePayload range) {

        public SourceRangePayload {
            sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
            range = Objects.requireNonNull(range, "range is required");
        }
    }

    /** Java Semantic Service 回傳的 follow-up HTTP contract data，Agent runtime capability exposure 延至下一個 milestone */
    public record AvailableFollowUp(String operation, FollowUpApi api, AvailableFollowUpRequest request) {

        public AvailableFollowUp {
            operation = Objects.requireNonNull(operation, "operation is required");
            api = Objects.requireNonNull(api, "api is required");
            request = Objects.requireNonNull(request, "request is required");
        }
    }

    /** follow-up 固定 HTTP method、path 與 operationId */
    public record FollowUpApi(String method, String path, String operationId) {

        public FollowUpApi {
            method = Objects.requireNonNull(method, "method is required");
            path = Objects.requireNonNull(path, "path is required");
            operationId = Objects.requireNonNull(operationId, "operationId is required");
        }
    }

    /** graph 與 discovery 回應可回傳的封閉 typed follow-up request */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(TargetFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverMethodImplementationsFollowUpRequest.class),
            @JsonSubTypes.Type(IdentityFollowUpRequest.class),
            @JsonSubTypes.Type(TypeMembersFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverConceptsFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverEventListenersFollowUpRequest.class),
            @JsonSubTypes.Type(ResolveSourceSymbolFollowUpRequest.class),
            @JsonSubTypes.Type(SourceSegmentFollowUpRequest.class)
    })
    public sealed interface AvailableFollowUpRequest permits TargetFollowUpRequest,
            DiscoverMethodImplementationsFollowUpRequest,
            IdentityFollowUpRequest, TypeMembersFollowUpRequest,
            DiscoverConceptsFollowUpRequest, DiscoverEventListenersFollowUpRequest,
            ResolveSourceSymbolFollowUpRequest,
            SourceSegmentFollowUpRequest {

        String repoId();

        String expectedRevision();
    }

    /** GET_SOURCE_SEGMENT 的完整 HTTP request payload */
    public record SourceSegmentFollowUpRequest(
            String repoId,
            String expectedRevision,
            SourceRangePayload location,
            Integer contextLines) implements AvailableFollowUpRequest {

        public SourceSegmentFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            location = Objects.requireNonNull(location, "location is required");
            contextLines = Objects.requireNonNull(contextLines, "contextLines is required");
        }
    }

    /** target follow-up 的共用 request，operation validator 會要求精確 optional 欄位組合 */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record TargetFollowUpRequest(String repoId, String expectedRevision, FollowUpTarget target,
                                        Optional<Integer> depth, Optional<Integer> offset, Optional<Integer> limit)
            implements AvailableFollowUpRequest {
        public TargetFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            target = Objects.requireNonNull(target, "target is required");
            depth = Optional.ofNullable(depth).orElse(Optional.empty());
            offset = Optional.ofNullable(offset).orElse(Optional.empty());
            limit = Optional.ofNullable(limit).orElse(Optional.empty());
        }
    }

    /** 方法實作探索的完整 follow-up request */
    public record DiscoverMethodImplementationsFollowUpRequest(String repoId, String expectedRevision,
                                                                MethodTargetPayload declarationTarget)
            implements AvailableFollowUpRequest {
        public DiscoverMethodImplementationsFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
        }
    }

    /** RESOLVE_CONCEPT 與 GET_EVIDENCE_SOURCE 共用的完整 follow-up request */
    public record IdentityFollowUpRequest(String repoId, String expectedRevision, FollowUpIdentity identity)
            implements AvailableFollowUpRequest {
        public IdentityFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** GET_TYPE_MEMBERS 與 DISCOVER_TYPE_MEMBERS 共用的完整 follow-up request */
    public record TypeMembersFollowUpRequest(String repoId, String expectedRevision,
                                                      SourceTypeIdentityPayload sourceType, List<String> memberKinds,
                                                      Optional<String> namePrefix, Integer offset, Integer limit)
            implements AvailableFollowUpRequest {
        public TypeMembersFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(memberKinds, "memberKinds are required"));
            namePrefix = Optional.ofNullable(namePrefix).orElse(Optional.empty());
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 概念探索續頁的完整 follow-up request */
    public record DiscoverConceptsFollowUpRequest(String repoId, String expectedRevision,
                                                  List<ConceptSearchTermPayload> terms, List<String> kinds,
                                                  String operator, Optional<String> packagePrefix,
                                                  Integer offset, Integer limit) implements AvailableFollowUpRequest {
        public DiscoverConceptsFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            operator = Objects.requireNonNull(operator, "operator is required");
            packagePrefix = Optional.ofNullable(packagePrefix).orElse(Optional.empty());
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 事件監聽器續頁的完整 follow-up request */
    public record DiscoverEventListenersFollowUpRequest(String repoId, String expectedRevision, String eventType,
                                                        Integer offset, Integer limit) implements AvailableFollowUpRequest {
        public DiscoverEventListenersFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            eventType = Objects.requireNonNull(eventType, "eventType is required");
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 來源符號解析的完整 follow-up request */
    public record ResolveSourceSymbolFollowUpRequest(String repoId, String expectedRevision,
                                                     SourceSymbolContextPayload context, String symbol,
                                                     Optional<Position> position) implements AvailableFollowUpRequest {
        public ResolveSourceSymbolFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            context = Objects.requireNonNull(context, "context is required");
            symbol = Objects.requireNonNull(symbol, "symbol is required");
            position = Optional.ofNullable(position).orElse(Optional.empty());
        }
    }

    /** 可由欄位集合區分的 concept 或 evidence identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(ConceptFollowUpIdentity.class),
            @JsonSubTypes.Type(EvidenceSourceFollowUpIdentity.class)})
    public sealed interface FollowUpIdentity permits ConceptFollowUpIdentity, EvidenceSourceFollowUpIdentity {
    }

    public sealed interface ConceptIdentityPayload permits ConceptFollowUpIdentity {
    }

    /** ten provider concept kinds 的單一 typed superset，validator 依 kind 收緊欄位組合 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ConceptFollowUpIdentity(String kind, Optional<SourceTypeIdentityPayload> sourceType,
                                          Optional<MethodTargetPayload> target,
                                          Optional<ConceptIdentityTargetPayload> identity,
                                          Optional<DeclarationSubjectPayload> declaration,
                                          Optional<AnnotationTypePayload> annotationType,
                                          Optional<DeclarationSubjectPayload> owner,
                                          Optional<TypeUsageLocationPayload> location,
                                          Optional<List<TypeUsagePathPayload>> path,
                                          Optional<ReferencedTypePayload> referencedType,
                                          Optional<String> httpVerb, Optional<String> route,
                                          Optional<String> broker, Optional<String> destination,
                                          Optional<String> triggerKind, Optional<String> triggerValue)
            implements FollowUpIdentity, ConceptIdentityPayload {
        public ConceptFollowUpIdentity {
            kind = Objects.requireNonNull(kind, "kind is required");
            sourceType = optional(sourceType);
            target = optional(target);
            identity = optional(identity);
            declaration = optional(declaration);
            annotationType = optional(annotationType);
            owner = optional(owner);
            location = optional(location);
            path = optional(path).map(List::copyOf);
            referencedType = optional(referencedType);
            httpVerb = optional(httpVerb);
            route = optional(route);
            broker = optional(broker);
            destination = optional(destination);
            triggerKind = optional(triggerKind);
            triggerValue = optional(triggerValue);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(SourceMemberIdentityPayload.TypeMember.class),
            @JsonSubTypes.Type(SourceMemberIdentityPayload.MethodScoped.class),
            @JsonSubTypes.Type(MapperStatementKeyPayload.class),
            @JsonSubTypes.Type(MapperStatementIdentityPayload.class)})
    public sealed interface ConceptIdentityTargetPayload permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped, MapperStatementKeyPayload, MapperStatementIdentityPayload {
    }

    /** provider 概念搜尋詞 */
    public record ConceptSearchTermPayload(String value, String matchMode) {
        public ConceptSearchTermPayload {
            value = Objects.requireNonNull(value, "value is required");
            matchMode = Objects.requireNonNull(matchMode, "matchMode is required");
        }
    }

    /** 來源符號解析 context */
    public record SourceSymbolContextPayload(JavaTypeIdentityPayload javaType, Optional<String> sourceFile,
                                             Optional<SourceSymbolMethodContextPayload> method) {
        public SourceSymbolContextPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = Optional.ofNullable(sourceFile).orElse(Optional.empty());
            method = Optional.ofNullable(method).orElse(Optional.empty());
        }
    }

    /** 來源符號的可選方法 context */
    public record SourceSymbolMethodContextPayload(String name, List<String> parameterTypes) {
        public SourceSymbolMethodContextPayload {
            name = Objects.requireNonNull(name, "name is required");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TypeDeclarationSubjectPayload.class, name = "TYPE"),
            @JsonSubTypes.Type(value = ResolvedMethodDeclarationSubjectPayload.class, name = "METHOD"),
            @JsonSubTypes.Type(value = UnresolvedMethodDeclarationSubjectPayload.class, name = "METHOD_UNRESOLVED"),
            @JsonSubTypes.Type(value = FieldDeclarationSubjectPayload.class, name = "FIELD")
    })
    public sealed interface DeclarationSubjectPayload permits TypeDeclarationSubjectPayload,
            ResolvedMethodDeclarationSubjectPayload, UnresolvedMethodDeclarationSubjectPayload,
            FieldDeclarationSubjectPayload {
    }

    public record TypeDeclarationSubjectPayload(String kind, SourceTypeIdentityPayload sourceType)
            implements DeclarationSubjectPayload {
        public TypeDeclarationSubjectPayload {
            kind = requiredKind(kind, "TYPE");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        }
    }

    public record ResolvedMethodDeclarationSubjectPayload(String kind, MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public ResolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record UnresolvedMethodDeclarationSubjectPayload(String kind, MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public UnresolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD_UNRESOLVED");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record FieldDeclarationSubjectPayload(String kind, SourceMemberIdentityPayload identity)
            implements DeclarationSubjectPayload {
        public FieldDeclarationSubjectPayload {
            kind = requiredKind(kind, "FIELD");
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "status", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ResolvedAnnotationTypePayload.class, name = "RESOLVED"),
            @JsonSubTypes.Type(value = UnresolvedAnnotationTypePayload.class, name = "UNRESOLVED")
    })
    public sealed interface AnnotationTypePayload permits ResolvedAnnotationTypePayload,
            UnresolvedAnnotationTypePayload {
    }

    public record ResolvedAnnotationTypePayload(String status, JavaTypeIdentityPayload javaType)
            implements AnnotationTypePayload {
        public ResolvedAnnotationTypePayload {
            status = requiredKind(status, "RESOLVED");
            javaType = Objects.requireNonNull(javaType, "javaType is required");
        }
    }

    public record UnresolvedAnnotationTypePayload(String status, String writtenName)
            implements AnnotationTypePayload {
        public UnresolvedAnnotationTypePayload {
            status = requiredKind(status, "UNRESOLVED");
            writtenName = Objects.requireNonNull(writtenName, "writtenName is required");
        }
    }

    public record TypeUsageLocationPayload(String slot, Integer index) {
        public TypeUsageLocationPayload {
            slot = Objects.requireNonNull(slot, "slot is required");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TypeArgumentPathPayload.class, name = "TYPE_ARGUMENT"),
            @JsonSubTypes.Type(value = WildcardExtendsBoundPathPayload.class, name = "WILDCARD_EXTENDS_BOUND"),
            @JsonSubTypes.Type(value = WildcardSuperBoundPathPayload.class, name = "WILDCARD_SUPER_BOUND"),
            @JsonSubTypes.Type(value = TypeVariableBoundPathPayload.class, name = "TYPE_VARIABLE_BOUND")
    })
    public sealed interface TypeUsagePathPayload permits TypeArgumentPathPayload,
            WildcardExtendsBoundPathPayload, WildcardSuperBoundPathPayload, TypeVariableBoundPathPayload {
    }

    public record TypeArgumentPathPayload(String kind, Integer index) implements TypeUsagePathPayload {
        public TypeArgumentPathPayload {
            kind = requiredKind(kind, "TYPE_ARGUMENT");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    public record WildcardExtendsBoundPathPayload(String kind) implements TypeUsagePathPayload {
        public WildcardExtendsBoundPathPayload {
            kind = requiredKind(kind, "WILDCARD_EXTENDS_BOUND");
        }
    }

    public record WildcardSuperBoundPathPayload(String kind) implements TypeUsagePathPayload {
        public WildcardSuperBoundPathPayload {
            kind = requiredKind(kind, "WILDCARD_SUPER_BOUND");
        }
    }

    public record TypeVariableBoundPathPayload(String kind, Integer index) implements TypeUsagePathPayload {
        public TypeVariableBoundPathPayload {
            kind = requiredKind(kind, "TYPE_VARIABLE_BOUND");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    public record ReferencedTypePayload(JavaTypeIdentityPayload javaType, Integer arrayDimensions) {
        public ReferencedTypePayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            arrayDimensions = Objects.requireNonNull(arrayDimensions, "arrayDimensions is required");
        }
    }

    public record MapperStatementKeyPayload(String namespace, String statementId) implements ConceptIdentityTargetPayload {
        public MapperStatementKeyPayload {
            namespace = Objects.requireNonNull(namespace, "namespace is required");
            statementId = Objects.requireNonNull(statementId, "statementId is required");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    public record MapperStatementIdentityPayload(MapperStatementKeyPayload statementKey, String resourcePath,
                                                 Optional<String> databaseId, Integer documentOrdinal,
                                                 String representation) implements ConceptIdentityTargetPayload {
        public MapperStatementIdentityPayload {
            statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
            resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
            databaseId = Optional.ofNullable(databaseId).orElse(Optional.empty());
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            representation = Objects.requireNonNull(representation, "representation is required");
        }
    }

    public record MapperFragmentIdentityPayload(String namespace, String fragmentId, String resourcePath,
                                                Integer documentOrdinal, String representation) {
        public MapperFragmentIdentityPayload {
            namespace = Objects.requireNonNull(namespace, "namespace is required");
            fragmentId = Objects.requireNonNull(fragmentId, "fragmentId is required");
            resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            representation = Objects.requireNonNull(representation, "representation is required");
        }
    }

    /** 需要 exact target 的 follow-up 共用封閉 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(MethodTargetPayload.class),
            @JsonSubTypes.Type(InternalReferenceFollowUpTarget.class)})
    public sealed interface FollowUpTarget permits MethodTargetPayload, InternalReferenceFollowUpTarget {
    }

    /** internal reference target 的 typed identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(SourceTypeIdentityPayload.class), @JsonSubTypes.Type(MethodTargetPayload.class),
            @JsonSubTypes.Type(SourceMemberIdentityPayload.class)})
    public sealed interface InternalReferenceIdentity permits SourceTypeIdentityPayload, MethodTargetPayload,
            SourceMemberIdentityPayload {
    }

    /** provider internal reference target 的封閉外觀 */
    public record InternalReferenceFollowUpTarget(String kind, InternalReferenceIdentity identity)
            implements FollowUpTarget {
        public InternalReferenceFollowUpTarget {
            kind = Objects.requireNonNull(kind, "kind is required");
            identity = Objects.requireNonNull(identity, "identity is required");
            boolean matches = ("TYPE".equals(kind) && identity instanceof SourceTypeIdentityPayload)
                    || ("METHOD".equals(kind) && identity instanceof MethodTargetPayload)
                    || ("MEMBER".equals(kind) && identity instanceof SourceMemberIdentityPayload);
            if (!matches) {
                throw new IllegalArgumentException("internal reference identity does not match kind");
            }
        }
    }

    /** provider evidence source identity */
    public sealed interface EvidenceSourceIdentityPayload permits EvidenceSourceFollowUpIdentity {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record EvidenceSourceFollowUpIdentity(String kind, Optional<MapperStatementIdentityPayload> statementIdentity,
                                                 Optional<MapperFragmentIdentityPayload> fragmentIdentity)
            implements FollowUpIdentity, EvidenceSourceIdentityPayload {
        public EvidenceSourceFollowUpIdentity {
            kind = Objects.requireNonNull(kind, "kind is required");
            statementIdentity = Optional.ofNullable(statementIdentity).orElse(Optional.empty());
            fragmentIdentity = Optional.ofNullable(fragmentIdentity).orElse(Optional.empty());
            boolean statementKind = "ANNOTATION_SQL".equals(kind) || "MAPPER_STATEMENT".equals(kind);
            boolean fragmentKind = "MAPPER_FRAGMENT".equals(kind);
            if (!statementKind && !fragmentKind) {
                throw new IllegalArgumentException("unsupported evidence identity kind");
            }
            if (statementKind != statementIdentity.isPresent() || fragmentKind != fragmentIdentity.isPresent()) {
                throw new IllegalArgumentException("evidence identity does not match kind");
            }
        }
    }

    private static String requiredKind(String value, String expected) {
        String required = requiredText(value, "kind");
        if (!expected.equals(required)) {
            throw new IllegalArgumentException("unexpected discriminator");
        }
        return required;
    }

    private static String requiredText(String value, String name) {
        String required = Objects.requireNonNull(value, name + " is required");
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }

    private static Integer requiredNonNegative(Integer value, String name) {
        Integer required = Objects.requireNonNull(value, name + " is required");
        if (required < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return required;
    }

    private static Long requiredNonNegative(Long value, String name) {
        Long required = Objects.requireNonNull(value, name + " is required");
        if (required < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return required;
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return Optional.ofNullable(value).orElse(Optional.empty());
    }

    /** 結構化探索的固定頁面計數 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PageResponse(Integer offset, Integer limit, Integer returnedCount, Long totalCount, Boolean hasMore) {
        public PageResponse {
            offset = requiredNonNegative(offset, "offset");
            limit = requiredNonNegative(limit, "limit");
            returnedCount = requiredNonNegative(returnedCount, "returnedCount");
            totalCount = requiredNonNegative(totalCount, "totalCount");
            hasMore = Objects.requireNonNull(hasMore, "hasMore is required");
        }
    }

    /** 結構化探索對來源快照的覆蓋摘要 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptCoverageResponse(String status, Integer scannedFileCount, Integer extractedFileCount,
                                          Integer syntaxFailedFileCount) {
        public ConceptCoverageResponse {
            status = requiredText(status, "status");
            scannedFileCount = requiredNonNegative(scannedFileCount, "scannedFileCount");
            extractedFileCount = requiredNonNegative(extractedFileCount, "extractedFileCount");
            syntaxFailedFileCount = requiredNonNegative(syntaxFailedFileCount, "syntaxFailedFileCount");
        }
    }

    /** 有界結果集合的計數與截斷狀態 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BoundedResultResponse(Integer limit, Integer returnedCount, Integer totalCount, Boolean truncated) {
        public BoundedResultResponse {
            limit = requiredNonNegative(limit, "limit");
            returnedCount = requiredNonNegative(returnedCount, "returnedCount");
            totalCount = requiredNonNegative(totalCount, "totalCount");
            truncated = Objects.requireNonNull(truncated, "truncated is required");
        }
    }

    /** 概念候選的最小投影及其後續操作 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptCandidateResponse(ConceptFollowUpIdentity identity, String displayValue,
                                           List<String> matchedTerms, String authority,
                                           Optional<ConceptCandidateDetailsResponse> details,
                                           List<ConceptEvidenceResponse> evidence,
                                           List<AvailableFollowUp> availableFollowUps) {
        public ConceptCandidateResponse {
            identity = Objects.requireNonNull(identity, "concept identity is required");
            matchedTerms = List.copyOf(Objects.requireNonNull(matchedTerms, "matched terms are required"));
            details = optional(details);
            evidence = List.copyOf(Objects.requireNonNull(evidence, "concept evidence is required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "concept follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptEvidenceResponse(ConceptFollowUpIdentity identity) {
        public ConceptEvidenceResponse {
            identity = Objects.requireNonNull(identity, "concept evidence identity is required");
        }
    }

    /** 概念候選的額外封閉細節 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = FieldConceptCandidateDetailsResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = MapperStatementConceptCandidateDetailsResponse.class, name = "MAPPER_STATEMENT")})
    public sealed interface ConceptCandidateDetailsResponse permits FieldConceptCandidateDetailsResponse,
            MapperStatementConceptCandidateDetailsResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldConceptCandidateDetailsResponse(String kind, FieldTypeReferenceResponse declaredType)
            implements ConceptCandidateDetailsResponse {
        public FieldConceptCandidateDetailsResponse {
            declaredType = Objects.requireNonNull(declaredType, "field concept declared type is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MapperStatementConceptCandidateDetailsResponse(String kind, MapperStatementMappingResponse mapping)
            implements ConceptCandidateDetailsResponse {
        public MapperStatementConceptCandidateDetailsResponse {
            mapping = Objects.requireNonNull(mapping, "mapper statement mapping is required");
        }
    }

    /** mapper statement 到 Java 方法的封閉 mapping 結果 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "status", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = ResolvedMapperStatementMappingResponse.class, name = "RESOLVED"),
            @JsonSubTypes.Type(value = AmbiguousMapperStatementMappingResponse.class, name = "AMBIGUOUS"),
            @JsonSubTypes.Type(value = UnresolvedMapperStatementMappingResponse.class, name = "UNRESOLVED")})
    public sealed interface MapperStatementMappingResponse permits ResolvedMapperStatementMappingResponse,
            AmbiguousMapperStatementMappingResponse, UnresolvedMapperStatementMappingResponse {
        MapperStatementKeyPayload statement();

        String status();

        List<MapperSourceMethodCandidateResponse> candidates();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolvedMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                         List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public ResolvedMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "resolved mapper statement is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "resolved mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AmbiguousMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                          List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public AmbiguousMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "ambiguous mapper statement is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "ambiguous mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UnresolvedMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                           String reason,
                                                           List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public UnresolvedMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "unresolved mapper statement is required");
            reason = Objects.requireNonNull(reason, "unresolved mapper reason is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "unresolved mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MapperSourceMethodCandidateResponse(MethodTargetPayload target,
                                                      List<AvailableFollowUp> availableFollowUps) {
        public MapperSourceMethodCandidateResponse {
            target = Objects.requireNonNull(target, "mapper source method target is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "mapper source method follow-ups are required"));
        }
    }

    /** 欄位宣告型別的遞迴封閉證據 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = NamedFieldTypeReferenceResponse.class, name = "NAMED"),
            @JsonSubTypes.Type(value = ParameterizedFieldTypeReferenceResponse.class, name = "PARAMETERIZED"),
            @JsonSubTypes.Type(value = PrimitiveFieldTypeReferenceResponse.class, name = "PRIMITIVE"),
            @JsonSubTypes.Type(value = ArrayFieldTypeReferenceResponse.class, name = "ARRAY"),
            @JsonSubTypes.Type(value = WildcardFieldTypeReferenceResponse.class, name = "WILDCARD"),
            @JsonSubTypes.Type(value = TypeVariableFieldTypeReferenceResponse.class, name = "TYPE_VARIABLE")})
    public sealed interface FieldTypeReferenceResponse permits NamedFieldTypeReferenceResponse,
            ParameterizedFieldTypeReferenceResponse, PrimitiveFieldTypeReferenceResponse,
            ArrayFieldTypeReferenceResponse, WildcardFieldTypeReferenceResponse,
            TypeVariableFieldTypeReferenceResponse {
        String kind();

        String writtenType();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NamedFieldTypeReferenceResponse(String kind, String writtenType, String simpleTypeName,
                                                  Optional<JavaTypeIdentityPayload> resolvedJavaType,
                                                  Boolean sourceDefined) implements FieldTypeReferenceResponse {
        public NamedFieldTypeReferenceResponse {
            kind = requiredText(kind, "kind");
            writtenType = Objects.requireNonNull(writtenType, "named written type is required");
            simpleTypeName = Objects.requireNonNull(simpleTypeName, "named simple type name is required");
            resolvedJavaType = optional(resolvedJavaType);
            sourceDefined = Objects.requireNonNull(sourceDefined, "sourceDefined is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ParameterizedFieldTypeReferenceResponse(String kind, String writtenType,
                                                          NamedFieldTypeReferenceResponse rawType,
                                                          List<FieldTypeReferenceResponse> typeArguments)
            implements FieldTypeReferenceResponse {
        public ParameterizedFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "parameterized written type is required");
            rawType = Objects.requireNonNull(rawType, "parameterized raw type is required");
            typeArguments = List.copyOf(Objects.requireNonNull(typeArguments,
                    "parameterized type arguments are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PrimitiveFieldTypeReferenceResponse(String kind, String writtenType) implements FieldTypeReferenceResponse {
        public PrimitiveFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "primitive written type is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ArrayFieldTypeReferenceResponse(String kind, String writtenType, FieldTypeReferenceResponse elementType,
                                                  Integer dimensions) implements FieldTypeReferenceResponse {
        public ArrayFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "array written type is required");
            elementType = Objects.requireNonNull(elementType, "array element type is required");
            dimensions = requiredNonNegative(dimensions, "dimensions");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WildcardFieldTypeReferenceResponse(String kind, String writtenType,
                                                     Optional<FieldTypeReferenceResponse> upperBound,
                                                     Optional<FieldTypeReferenceResponse> lowerBound,
                                                     Boolean sourceDefined) implements FieldTypeReferenceResponse {
        public WildcardFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "wildcard written type is required");
            upperBound = optional(upperBound);
            lowerBound = optional(lowerBound);
            sourceDefined = Objects.requireNonNull(sourceDefined, "sourceDefined is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TypeVariableFieldTypeReferenceResponse(String kind, String writtenType, String variableName,
                                                         List<FieldTypeReferenceResponse> upperBounds,
                                                         Boolean sourceDefined) implements FieldTypeReferenceResponse {
        public TypeVariableFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "type variable written type is required");
            variableName = Objects.requireNonNull(variableName, "type variable name is required");
            upperBounds = List.copyOf(Objects.requireNonNull(upperBounds, "type variable bounds are required"));
            sourceDefined = Objects.requireNonNull(sourceDefined, "sourceDefined is required");
        }
    }

    /** 結構化概念探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverConceptsResponse(String repoId, String analyzedRevision, List<String> normalizedTerms,
                                           List<String> searchedKinds, List<String> supportedKinds,
                                           List<String> limitations, List<ConceptCandidateResponse> candidates,
                                           PageResponse page, ConceptCoverageResponse coverage,
                                           List<IssueSummaryResponse> issueSummaries,
                                           List<AvailableFollowUp> availableFollowUps,
                                           List<UnavailableFollowUpResponse> unavailableFollowUps) {
        public DiscoverConceptsResponse {
            normalizedTerms = List.copyOf(Objects.requireNonNull(normalizedTerms, "normalized terms are required"));
            searchedKinds = List.copyOf(Objects.requireNonNull(searchedKinds, "searched kinds are required"));
            supportedKinds = List.copyOf(Objects.requireNonNull(supportedKinds, "supported kinds are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "concept candidates are required"));
            page = Objects.requireNonNull(page, "concept page is required");
            coverage = Objects.requireNonNull(coverage, "concept coverage is required");
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "concept issues are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "concept response follow-ups are required"));
            unavailableFollowUps = List.copyOf(Objects.requireNonNull(unavailableFollowUps,
                    "unavailable concept follow-ups are required"));
        }
    }

    /** 概念 resolve 的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolveConceptResponse(String repoId, String analyzedRevision, ConceptCandidateResponse candidate) {
        public ResolveConceptResponse {
            candidate = Objects.requireNonNull(candidate, "resolved concept candidate is required");
        }
    }

    /** 事件監聽器候選及其精確 target */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EventListenerCandidateResponse(MethodTargetPayload target, List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
                                                 TextRangePayload sourceRange,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public EventListenerCandidateResponse {
            target = Objects.requireNonNull(target, "listener target is required");
            listenerAnnotations = List.copyOf(Objects.requireNonNull(listenerAnnotations,
                    "listener annotations are required"));
            sourceRange = Objects.requireNonNull(sourceRange, "listener source range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "listener follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ListenerAnnotationEvidenceResponse(String kind, String matchKind) {
    }

    /** 事件監聽器的封閉 issue 摘要 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ListenerObservationSummaryResponse(String code, Long totalCount, List<SourceRangePayload> samples) {
        public ListenerObservationSummaryResponse {
            code = requiredText(code, "code");
            totalCount = requiredNonNegative(totalCount, "totalCount");
            samples = List.copyOf(Objects.requireNonNull(samples, "listener observation samples are required"));
        }
    }

    /** 事件監聽器探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverEventListenersResponse(String repoId, String analyzedRevision, String requestedEventType,
                                                 List<EventListenerCandidateResponse> candidates, PageResponse page,
                                                 List<ListenerObservationSummaryResponse> observationSummaries,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public DiscoverEventListenersResponse {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "listener candidates are required"));
            page = Objects.requireNonNull(page, "listener page is required");
            observationSummaries = List.copyOf(Objects.requireNonNull(observationSummaries,
                    "listener observations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "listener response follow-ups are required"));
        }
    }

    /** 方法實作候選及其後續操作 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodImplementationCandidateResponse(MethodTargetPayload target, Boolean primary, List<String> qualifiers,
                                                        List<String> profiles,
                                                        List<AvailableFollowUp> availableFollowUps) {
        public MethodImplementationCandidateResponse {
            target = Objects.requireNonNull(target, "implementation target is required");
            primary = Objects.requireNonNull(primary, "primary is required");
            qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "implementation qualifiers are required"));
            profiles = List.copyOf(Objects.requireNonNull(profiles, "implementation profiles are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "implementation follow-ups are required"));
        }
    }

    /** 方法實作探索的封閉 resolution */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodImplementationResolutionResponse(String status, List<IssueSummaryResponse> issueSummaries) {
        public MethodImplementationResolutionResponse {
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries,
                    "implementation issues are required"));
        }
    }

    /** 方法實作探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverMethodImplementationsResponse(String repoId, String revision,
                                                        MethodTargetPayload requestedTarget,
                                                        List<MethodImplementationCandidateResponse> candidates,
                                                        BoundedResultResponse limits,
                                                        MethodImplementationResolutionResponse resolution) {
        public DiscoverMethodImplementationsResponse {
            requestedTarget = Objects.requireNonNull(requestedTarget, "requested method target is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "implementation candidates are required"));
            limits = Objects.requireNonNull(limits, "implementation limits are required");
            resolution = Objects.requireNonNull(resolution, "implementation resolution is required");
        }
    }

    /** 型別成員回應的封閉變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MethodTypeMemberResponse.class, name = "METHOD"),
            @JsonSubTypes.Type(value = FieldTypeMemberResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = EnumConstantTypeMemberResponse.class, name = "ENUM_CONSTANT"),
            @JsonSubTypes.Type(value = RecordComponentTypeMemberResponse.class, name = "RECORD_COMPONENT")
    })
    public sealed interface TypeMemberResponse permits MethodTypeMemberResponse, FieldTypeMemberResponse,
            EnumConstantTypeMemberResponse, RecordComponentTypeMemberResponse {
        List<AvailableFollowUp> availableFollowUps();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodTypeMemberResponse(String kind, MethodTargetPayload target,
                                           List<AvailableFollowUp> availableFollowUps) implements TypeMemberResponse {
        public MethodTypeMemberResponse {
            kind = requiredKind(kind, "METHOD");
            target = Objects.requireNonNull(target, "member method target is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member method follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldTypeMemberResponse(String kind, SourceMemberIdentityPayload identity, String writtenType,
                                          Optional<String> resolvedType, List<String> annotations, List<String> limitations,
                                          List<AvailableFollowUp> availableFollowUps) implements TypeMemberResponse {
        public FieldTypeMemberResponse {
            kind = requiredKind(kind, "FIELD");
            identity = Objects.requireNonNull(identity, "member field identity is required");
            writtenType = Objects.requireNonNull(writtenType, "member field written type is required");
            resolvedType = optional(resolvedType);
            annotations = List.copyOf(Objects.requireNonNull(annotations, "member field annotations are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "member field limitations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member field follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EnumConstantTypeMemberResponse(String kind, SourceMemberIdentityPayload identity,
                                                 TextRangePayload declarationRange, List<String> annotations,
                                                 List<AvailableFollowUp> availableFollowUps)
            implements TypeMemberResponse {
        public EnumConstantTypeMemberResponse {
            kind = requiredKind(kind, "ENUM_CONSTANT");
            identity = Objects.requireNonNull(identity, "enum constant identity is required");
            declarationRange = Objects.requireNonNull(declarationRange,
                    "enum constant declaration range is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations,
                    "enum constant annotations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "enum constant follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RecordComponentTypeMemberResponse(String kind, SourceMemberIdentityPayload identity,
                                                    String writtenType, Optional<String> resolvedType,
                                                    TextRangePayload declarationRange, List<String> annotations,
                                                    List<AvailableFollowUp> availableFollowUps)
            implements TypeMemberResponse {
        public RecordComponentTypeMemberResponse {
            kind = requiredKind(kind, "RECORD_COMPONENT");
            identity = Objects.requireNonNull(identity, "record component identity is required");
            writtenType = Objects.requireNonNull(writtenType, "record component written type is required");
            resolvedType = optional(resolvedType);
            declarationRange = Objects.requireNonNull(declarationRange,
                    "record component declaration range is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations,
                    "record component annotations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "record component follow-ups are required"));
        }
    }

    /** 型別成員探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverTypeMembersResponse(String repoId, String analyzedRevision, SourceTypeIdentityPayload sourceType,
                                              String typeKind, List<String> annotations, List<String> implementedTypes,
                                              List<String> extendedTypes,
                                              List<TypeMemberResponse> members, PageResponse page,
                                              ConceptCoverageResponse coverage,
                                              List<AvailableFollowUp> availableFollowUps) {
        public DiscoverTypeMembersResponse {
            sourceType = Objects.requireNonNull(sourceType, "member source type is required");
            typeKind = Objects.requireNonNull(typeKind, "type member kind is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations, "type annotations are required"));
            implementedTypes = List.copyOf(Objects.requireNonNull(implementedTypes, "implemented types are required"));
            extendedTypes = List.copyOf(Objects.requireNonNull(extendedTypes, "extended types are required"));
            members = List.copyOf(Objects.requireNonNull(members, "type members are required"));
            page = Objects.requireNonNull(page, "member page is required");
            coverage = Objects.requireNonNull(coverage, "member coverage is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member response follow-ups are required"));
        }
    }

    /** 可 materialize 的來源片段 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSegmentPayload(SourceRangePayload location, String content,
                                       Optional<SourceRangePayload> nextLocation) {
        public SourceSegmentPayload {
            location = Objects.requireNonNull(location, "source segment location is required");
            content = Objects.requireNonNull(content, "source segment content is required");
            nextLocation = optional(nextLocation);
        }
    }

    /** 方法來源、evidence 與 range continuation 共用的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodSourceResponse(String repoId, String analyzedRevision, SourceRangePayload declarationLocation,
                                       SourceSegmentPayload segment, List<AvailableFollowUp> availableFollowUps) {
        public MethodSourceResponse {
            declarationLocation = Objects.requireNonNull(declarationLocation, "method declaration location is required");
            segment = Objects.requireNonNull(segment, "method source segment is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "method source follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSegmentResponse(String repoId, String analyzedRevision, SourceSegmentPayload segment,
                                        Boolean contextTruncated, List<AvailableFollowUp> availableFollowUps) {
        public SourceSegmentResponse {
            segment = Objects.requireNonNull(segment, "source segment is required");
            contextTruncated = Objects.requireNonNull(contextTruncated, "contextTruncated is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "source segment follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EvidenceSourceResponse(String repoId, String analyzedRevision,
                                         EvidenceSourceFollowUpIdentity identity, SourceRangePayload location,
                                         SourceSegmentPayload segment, List<AvailableFollowUp> availableFollowUps) {
        public EvidenceSourceResponse {
            identity = Objects.requireNonNull(identity, "evidence identity is required");
            location = Objects.requireNonNull(location, "evidence location is required");
            segment = Objects.requireNonNull(segment, "evidence segment is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "evidence follow-ups are required"));
        }
    }

    /** source symbol context candidate 的精確 provider 變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceTypeContextCandidateResponse.class, name = "SOURCE_TYPE"),
            @JsonSubTypes.Type(value = SourceMethodContextCandidateResponse.class, name = "METHOD")})
    public sealed interface SourceContextCandidateResponse permits SourceTypeContextCandidateResponse,
            SourceMethodContextCandidateResponse {
        AvailableFollowUp retry();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceTypeContextCandidateResponse(String kind, String sourceFile, AvailableFollowUp retry)
            implements SourceContextCandidateResponse {
        public SourceTypeContextCandidateResponse {
            sourceFile = Objects.requireNonNull(sourceFile, "source type context file is required");
            retry = Objects.requireNonNull(retry, "source type context retry is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceMethodContextCandidateResponse(String kind, MethodTargetPayload target, AvailableFollowUp retry)
            implements SourceContextCandidateResponse {
        public SourceMethodContextCandidateResponse {
            target = Objects.requireNonNull(target, "source method context target is required");
            retry = Objects.requireNonNull(retry, "source method context retry is required");
        }
    }

    /** source symbol candidate 的精確 provider 變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "RECORD_COMPONENT"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "PARAMETER"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "LOCAL_VARIABLE"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "ENUM_CONSTANT"),
            @JsonSubTypes.Type(value = StaticConstantSourceSymbolCandidateResponse.class, name = "STATIC_CONSTANT"),
            @JsonSubTypes.Type(value = MethodSourceSymbolCandidateResponse.class, name = "METHOD"),
            @JsonSubTypes.Type(value = SourceTypeSymbolCandidateResponse.class, name = "SOURCE_TYPE")
    })
    public sealed interface SourceSymbolCandidateResponse permits VariableLikeSourceSymbolCandidateResponse,
            StaticConstantSourceSymbolCandidateResponse, MethodSourceSymbolCandidateResponse,
            SourceTypeSymbolCandidateResponse {
        String kind();

        TextRangePayload declarationRange();

        TextRangePayload representativeOccurrence();

        Integer occurrenceCount();

        List<AvailableFollowUp> availableFollowUps();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record VariableLikeSourceSymbolCandidateResponse(String kind, SourceMemberIdentityPayload identity,
                                                            DeclaredTypeResponse declaredType,
                                                            TextRangePayload declarationRange,
                                                            TextRangePayload representativeOccurrence,
                                                            Integer occurrenceCount,
                                                            List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public VariableLikeSourceSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "variable symbol identity is required");
            declaredType = Objects.requireNonNull(declaredType, "variable declared type is required");
            declarationRange = Objects.requireNonNull(declarationRange, "variable declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "variable representative occurrence is required");
            occurrenceCount = requiredNonNegative(occurrenceCount, "occurrenceCount");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "variable symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StaticConstantSourceSymbolCandidateResponse(String kind, SourceMemberIdentityPayload identity,
                                                              DeclaredTypeResponse declaredType, String initializerSource,
                                                              TextRangePayload declarationRange,
                                                              TextRangePayload representativeOccurrence,
                                                              Integer occurrenceCount,
                                                              List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public StaticConstantSourceSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "constant symbol identity is required");
            declaredType = Objects.requireNonNull(declaredType, "constant declared type is required");
            initializerSource = Objects.requireNonNull(initializerSource, "constant initializer source is required");
            declarationRange = Objects.requireNonNull(declarationRange, "constant declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "constant representative occurrence is required");
            occurrenceCount = requiredNonNegative(occurrenceCount, "occurrenceCount");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "constant symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodSourceSymbolCandidateResponse(String kind, MethodTargetPayload target,
                                                      TextRangePayload declarationRange,
                                                      TextRangePayload representativeOccurrence, Integer occurrenceCount,
                                                      List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public MethodSourceSymbolCandidateResponse {
            target = Objects.requireNonNull(target, "method symbol target is required");
            declarationRange = Objects.requireNonNull(declarationRange, "method declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "method representative occurrence is required");
            occurrenceCount = requiredNonNegative(occurrenceCount, "occurrenceCount");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "method symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceTypeSymbolCandidateResponse(String kind, SourceTypeIdentityPayload identity,
                                                    TextRangePayload declarationRange,
                                                    TextRangePayload representativeOccurrence, Integer occurrenceCount,
                                                    List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public SourceTypeSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "source type symbol identity is required");
            declarationRange = Objects.requireNonNull(declarationRange, "source type declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "source type representative occurrence is required");
            occurrenceCount = requiredNonNegative(occurrenceCount, "occurrenceCount");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "source type symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeclaredTypeResponse(String writtenType, Optional<String> resolvedType) {
        public DeclaredTypeResponse {
            writtenType = Objects.requireNonNull(writtenType, "declared written type is required");
            resolvedType = optional(resolvedType);
        }
    }

    /** source symbol resolve 的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolveSourceSymbolResponse(String repoId, String analyzedRevision, String status,
                                              List<SourceContextCandidateResponse> contextCandidates,
                                              BoundedResultResponse contextCandidateLimits,
                                              List<SourceSymbolCandidateResponse> candidates,
                                              List<SourceSymbolIssueSummaryResponse> issues) {
        public ResolveSourceSymbolResponse {
            contextCandidates = List.copyOf(Objects.requireNonNull(contextCandidates,
                    "source symbol context candidates are required"));
            contextCandidateLimits = Objects.requireNonNull(contextCandidateLimits,
                    "source symbol context candidate limits are required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "source symbol candidates are required"));
            issues = List.copyOf(Objects.requireNonNull(issues, "source symbol issues are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSymbolIssueSummaryResponse(String code, Integer count) {
        public SourceSymbolIssueSummaryResponse {
            code = requiredText(code, "code");
            count = requiredNonNegative(count, "count");
        }
    }

    /** internal reference 代表 occurrence */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReferenceOccurrenceResponse(TextRangePayload range, List<AvailableFollowUp> availableFollowUps) {
        public ReferenceOccurrenceResponse {
            range = Objects.requireNonNull(range, "reference range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference occurrence follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReferenceGroupResponse(InternalReferenceContextResponse context,
                                         List<ReferenceOccurrenceResponse> representativeReferences,
                                         BoundedResultResponse limits, List<AvailableFollowUp> availableFollowUps,
                                         List<UnavailableFollowUpResponse> unavailableFollowUps) {
        public ReferenceGroupResponse {
            context = Objects.requireNonNull(context, "reference group context is required");
            representativeReferences = List.copyOf(Objects.requireNonNull(representativeReferences,
                    "representative references are required"));
            limits = Objects.requireNonNull(limits, "reference limits are required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference group follow-ups are required"));
            unavailableFollowUps = List.copyOf(Objects.requireNonNull(unavailableFollowUps,
                    "unavailable reference group follow-ups are required"));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = InternalReferenceTypeContextResponse.class, name = "TYPE"),
            @JsonSubTypes.Type(value = InternalReferenceMethodContextResponse.class, name = "METHOD")})
    public sealed interface InternalReferenceContextResponse permits InternalReferenceTypeContextResponse,
            InternalReferenceMethodContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceTypeContextResponse(String kind, SourceTypeIdentityPayload sourceType)
            implements InternalReferenceContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceMethodContextResponse(String kind, MethodTargetPayload method)
            implements InternalReferenceContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceTargetDeclarationResponse(InternalReferenceFollowUpTarget target,
                                                             TextRangePayload declarationRange,
                                                             List<AvailableFollowUp> availableFollowUps) {
        public InternalReferenceTargetDeclarationResponse {
            target = Objects.requireNonNull(target, "reference declaration target is required");
            declarationRange = Objects.requireNonNull(declarationRange, "reference declaration range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference declaration follow-ups are required"));
        }
    }

    /** internal reference 成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FindInternalReferencesResponse(String repoId, String analyzedRevision, String status,
                                                 InternalReferenceTargetDeclarationResponse targetDeclaration,
                                                 Integer totalReferenceCount,
                                                 List<ReferenceGroupResponse> referenceGroups, PageResponse page,
                                                 List<IssueSummaryResponse> issueSummaries,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public FindInternalReferencesResponse {
            targetDeclaration = Objects.requireNonNull(targetDeclaration, "reference target declaration is required");
            totalReferenceCount = requiredNonNegative(totalReferenceCount, "totalReferenceCount");
            referenceGroups = List.copyOf(Objects.requireNonNull(referenceGroups,
                    "reference groups are required"));
            page = Objects.requireNonNull(page, "reference page is required");
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "reference issues are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference response follow-ups are required"));
        }
    }

    /** provider 已封閉的 issue code/count */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record IssueSummaryResponse(String code, Integer count) {
        public IssueSummaryResponse {
            code = requiredText(code, "code");
            count = requiredNonNegative(count, "count");
        }
    }

    /** provider 不可執行後續動作的封閉理由 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UnavailableFollowUpResponse(String reason, String recommendedAction) {
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTargetPayload target, List<MethodTargetPayload> candidates,
                                   String requestId) {
    }
}
