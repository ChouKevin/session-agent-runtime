package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class MessageJobService implements MessageJobPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageJobService.class);
    private static final String MODEL_CALL_LIMIT_REACHED = "MODEL_CALL_LIMIT_REACHED";
    private static final String MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID";
    private static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    private static final String CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE";
    private static final String COMPACTION_FAILED = "COMPACTION_FAILED";
    private static final String INVALID_CONVERSATION_HISTORY = "INVALID_CONVERSATION_HISTORY";

    private final ConversationStore conversationStore;
    private final ConversationModel conversationModel;
    private final ToolCatalog toolCatalog;
    private final Clock clock;
    private final int maxModelCalls;
    private final MessageJobRetryPolicy retryPolicy;
    private final ConversationTelemetry telemetry;
    private final ContextUsageEstimator contextUsageEstimator = new ContextUsageEstimator();

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog,
            Clock clock) {
        this(conversationStore, conversationModel, toolCatalog, clock, Integer.MAX_VALUE,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog,
            Clock clock,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this(conversationStore, conversationModel, toolCatalog, clock, Integer.MAX_VALUE, retryPolicy, telemetry);
    }

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog,
            Clock clock,
            int maxModelCalls,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModel = Objects.requireNonNull(conversationModel, "Conversation model must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "Tool catalog must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        Assert.isTrue(maxModelCalls > 0, "Maximum model calls must be positive");
        this.maxModelCalls = maxModelCalls;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "Message job retry policy must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    @Override
    public MessageJobProcessingResult process(MessageWorkClaim claim, WorkGuard workGuard) {
        Assert.notNull(claim, "Message work claim must not be null");
        Assert.notNull(workGuard, "Work guard must not be null");
        ProcessingResultTracker resultTracker = new ProcessingResultTracker();
        try {
            processClaim(claim, workGuard, resultTracker);
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
        } catch (ConversationStoreFailure failure) {
            recoverStorageFailure(claim, workGuard, failure, resultTracker);
        }
        return resultTracker.result(workGuard);
    }

    private void processClaim(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker resultTracker) {
        ModelDescriptor modelDescriptor = conversationModel.descriptor();
        ModelRouteId routeId = conversationModel.routeId();
        if (Objects.isNull(modelDescriptor) && !Objects.isNull(routeId)) {
            modelDescriptor = new ModelDescriptor(routeId, "unspecified", 1);
        }
        if (!Objects.isNull(modelDescriptor)) {
            routeId = modelDescriptor.routeId();
        }
        ModelDescriptor activeModelDescriptor = modelDescriptor;
        try {
            conversationStore.bindModelRoute(claim, routeId);
        } catch (ModelRouteMismatchException exception) {
            appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return;
        }
        while (guard.stillOwned()) {
            List<SessionMessage> history = conversationStore.loadHistory(claim.sessionId());
            Map<SessionSequence, ModelContinuation> continuations = Optional.ofNullable(conversationStore.loadContinuations(claim))
                    .orElseGet(Map::of);
            if (hasContinuationForAnotherRoute(continuations, routeId)) {
                appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE, resultTracker);
                return;
            }
            try (ToolSnapshot tools = toolCatalog.snapshot()) {
                ReservationState reservation = new ReservationState();
                ContextState context = contextState(claim, history, continuations, tools, activeModelDescriptor);
                if (context.requiresCompaction()) {
                    if (!compact(claim, guard, reservation, context, ContextCompaction.Reason.THRESHOLD, resultTracker)) {
                        return;
                    }
                    continue;
                }
                ModelRequest request = context.request();
                String requestShapeFingerprint = context.requestShapeFingerprint();
                ModelReply reply;
                Optional<ModelContinuation> continuation;
                ModelCallResult result;
                long requestStartedAt = System.nanoTime();
                try {
                    result = conversationModel.respond(request, () -> reserveAndLogModelRequest(claim, guard, reservation,
                            activeModelDescriptor, history.size(), tools.definitions().size()), usage -> { });
                    reply = result.reply();
                    continuation = result.continuation();
                } catch (BudgetExhausted exception) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)),
                            ConversationStore.JobUpdate.COMPLETE, resultTracker);
                    return;
                } catch (ModelCallFailure failure) {
                    if (failure.kind() == ModelCallFailure.Kind.INVALID_HISTORY) {
                        logModelFailure(claim, reservation.ordinal(), failure.kind(), elapsedSince(requestStartedAt));
                        appendRuntime(claim, guard, List.of(runtime(INVALID_CONVERSATION_HISTORY)),
                                ConversationStore.JobUpdate.COMPLETE, resultTracker);
                        return;
                    }
                    if (failure.kind() == ModelCallFailure.Kind.CONTEXT_TOO_LARGE
                            && recoverOverflow(claim, guard, reservation, context, elapsedSince(requestStartedAt), resultTracker)) {
                        continue;
                    }
                    if (handleModelFailure(claim, guard, reservation, failure,
                            elapsedSince(requestStartedAt), resultTracker)) {
                        continue;
                    }
                    return;
                }
                if (continuation.isPresent() && !routeId.equals(continuation.orElseThrow().modelRouteId())) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)),
                            ConversationStore.JobUpdate.COMPLETE, resultTracker);
                    return;
                }
                if (!guard.stillOwned()) {
                    return;
                }
                int ordinal = reservation.ordinal();
                logModelResponse(claim, activeModelDescriptor, ordinal, reply, result.usage(), elapsedSince(requestStartedAt));
                if (reply instanceof ModelReply.Text text) {
                    appendResponse(claim, guard, List.of(new ConversationStore.AssistantData(text.message())),
                            ConversationStore.JobUpdate.COMPLETE, Optional.empty(), checkpoint(result, activeModelDescriptor, ordinal,
                                    requestShapeFingerprint, context.compaction().map(ContextCompaction::generation).orElse(0L)), resultTracker);
                    return;
                }
                ModelReply.UseTools useTools = (ModelReply.UseTools) reply;
                if (ordinal == maxModelCalls) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)),
                            ConversationStore.JobUpdate.COMPLETE, resultTracker);
                    return;
                }
                List<ConversationStore.MessageData> messages = toolBatch(claim, guard, ordinal, useTools, tools);
                if (!guard.stillOwned()) {
                    return;
                }
                if (!appendResponse(claim, guard, messages, ConversationStore.JobUpdate.KEEP_WORKING, continuation,
                        checkpoint(result, activeModelDescriptor, ordinal, requestShapeFingerprint,
                                context.compaction().map(ContextCompaction::generation).orElse(0L)), resultTracker)) {
                    return;
                }
            }
        }
    }

    private Optional<ConversationStore.UsageCheckpointData> checkpoint(ModelCallResult result, ModelDescriptor descriptor, int ordinal,
            String requestShapeFingerprint, long compactGeneration) {
        if (!result.usage().available() || Objects.isNull(descriptor) || !org.springframework.util.StringUtils.hasText(requestShapeFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new ConversationStore.UsageCheckpointData(descriptor, ordinal, result.usage().promptTokens(),
                result.usage().completionTokens(), result.usage().totalTokens(), requestShapeFingerprint, compactGeneration));
    }

    private ContextState contextState(
            MessageWorkClaim claim,
            List<SessionMessage> history,
            Map<SessionSequence, ModelContinuation> continuations,
            ToolSnapshot tools,
            ModelDescriptor descriptor) {
        if (Objects.isNull(descriptor) || "unspecified".equals(descriptor.modelId())
                || !org.springframework.util.StringUtils.hasText(conversationModel.systemPrompt())) {
            return new ContextState(new ModelRequest(history, continuations, tools), "", Optional.empty(), 0, false, Optional.empty());
        }
        Optional<ContextCompaction> compaction = conversationStore.loadCompaction(claim.sessionId());
        List<SessionMessage> visibleHistory = compactedSuffix(history, compaction);
        Map<SessionSequence, ModelContinuation> visibleContinuations = continuationsAfter(continuations, compaction);
        Optional<ContextSummary> summary = compaction.map(ContextCompaction::summary);
        long generation = compaction.map(ContextCompaction::generation).orElse(0L);
        ContextUsageProjection projection = new ContextUsageProjection(descriptor, conversationModel.systemPrompt(), tools.definitions(),
                visibleHistory, generation, summary);
        String fingerprint = contextUsageEstimator.requestShapeFingerprint(projection);
        long estimate = contextUsageEstimator.estimate(projection,
                conversationStore.loadUsageCheckpoint(claim.sessionId(), descriptor, fingerprint, generation)).tokens();
        long threshold = fourFifths(descriptor.contextWindowTokens());
        if (estimate >= threshold) {
            logCompaction(claim, "compact_threshold_reached", "THRESHOLD_REACHED", estimate, descriptor.contextWindowTokens());
        }
        return new ContextState(new ModelRequest(visibleHistory, visibleContinuations, tools, summary), fingerprint, compaction,
                estimate, estimate >= threshold, Optional.of(projection));
    }

    private boolean recoverOverflow(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ContextState context,
            Duration duration,
            ProcessingResultTracker resultTracker) {
        logModelFailure(claim, reservation.ordinal(), ModelCallFailure.Kind.CONTEXT_TOO_LARGE, duration);
        if (conversationStore.hasOverflowCompaction(claim.messageJobId())) {
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        return compact(claim, guard, new ReservationState(), context, ContextCompaction.Reason.OVERFLOW, resultTracker);
    }

    private boolean compact(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ContextState context,
            ContextCompaction.Reason reason,
            ProcessingResultTracker resultTracker) {
        if (context.projection().isEmpty()) {
            logCompaction(claim, "compact_failure", "NO_PROJECTION", context.estimateTokens(), 0L);
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        ContextUsageProjection projection = context.projection().orElseThrow();
        Optional<CompactionBoundary> boundary = selectBoundary(claim, context, projection.model());
        if (boundary.isEmpty()) {
            logCompaction(claim, "compact_failure", "NO_BOUNDARY", context.estimateTokens(), projection.model().contextWindowTokens());
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        CompactionBoundary selected = boundary.orElseThrow();
        logCompaction(claim, "compact_started", reason.name(), context.estimateTokens(), projection.model().contextWindowTokens());
        String summary;
        try {
            summary = conversationModel.summarize(new ContextCompactionRequest(context.compaction().map(ContextCompaction::summary),
                    selected.history()), () -> reserveAndLogModelRequest(claim, guard, reservation, projection.model(),
                    selected.history().size(), 0));
        } catch (BudgetExhausted exception) {
            logCompaction(claim, "compact_failure", "MODEL_LIMIT", context.estimateTokens(), projection.model().contextWindowTokens());
            appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        } catch (ModelCallFailure failure) {
            logCompaction(claim, "compact_failure", failureCategory(failure.kind()), context.estimateTokens(), projection.model().contextWindowTokens());
            return handleCompactionFailure(claim, guard, reservation, failure, resultTracker);
        }
        if (!guard.stillOwned()) {
            return false;
        }
        ContextSummary compactedSummary;
        try {
            compactedSummary = new ContextSummary(summary);
        } catch (IllegalArgumentException exception) {
            logCompaction(claim, "compact_failure", "SUMMARY_INVALID", context.estimateTokens(), projection.model().contextWindowTokens());
            appendRuntime(claim, guard, List.of(runtime(COMPACTION_FAILED)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        ContextUsageProjection afterProjection = new ContextUsageProjection(projection.model(), projection.systemPrompt(),
                projection.toolDefinitions(), selected.suffix(), context.compaction().map(ContextCompaction::generation).orElse(0L) + 1,
                Optional.of(compactedSummary));
        long afterEstimate = contextUsageEstimator.estimate(afterProjection, Optional.empty()).tokens();
        if (afterEstimate >= fourFifths(projection.model().contextWindowTokens())) {
            logCompaction(claim, "compact_failure", "SUMMARY_TOO_LARGE", afterEstimate, projection.model().contextWindowTokens());
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        try {
            conversationStore.compact(claim, new ConversationStore.CompactionData(
                    context.compaction().map(ContextCompaction::generation).orElse(0L) + 1, reason, compactedSummary.text(),
                    selected.boundary(), projection.model(), context.requestShapeFingerprint(), context.estimateTokens(), afterEstimate), clock.instant());
            logCompaction(claim, "compact_succeeded", reason.name(), afterEstimate, projection.model().contextWindowTokens());
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            logCompaction(claim, "compact_failure", "OWNERSHIP_LOST", context.estimateTokens(), projection.model().contextWindowTokens());
            resultTracker.ownershipLost();
            return false;
        }
    }

    private boolean handleCompactionFailure(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ModelCallFailure failure,
            ProcessingResultTracker resultTracker) {
        logModelFailure(claim, reservation.ordinal(), failure.kind(), Duration.ZERO);
        if (failure.kind() == ModelCallFailure.Kind.TRANSIENT && reservation.ordinal() < maxModelCalls
                && scheduleRetry(claim, guard, resultTracker)) {
            return false;
        }
        appendRuntime(claim, guard, List.of(runtime(COMPACTION_FAILED)),
                ConversationStore.JobUpdate.COMPLETE, resultTracker);
        return false;
    }

    private Optional<CompactionBoundary> selectBoundary(MessageWorkClaim claim, ContextState context, ModelDescriptor descriptor) {
        List<SessionMessage> full = conversationStore.loadHistory(claim.sessionId());
        int start = context.compaction().map(compaction -> indexAfter(full, compaction.coveredThrough())).orElse(0);
        for (int index = full.size() - 1; index >= start; index--) {
            int end = completeBoundaryEnd(full, index);
            if (end < index || !isResponseBoundary(full.get(index))) {
                continue;
            }
            List<SessionMessage> candidate = full.subList(start, end + 1);
            ContextUsageProjection summaryProjection = new ContextUsageProjection(descriptor, conversationModel.compactionPrompt(), List.of(), candidate,
                    0, context.compaction().map(ContextCompaction::summary));
            long summaryEstimate = contextUsageEstimator.estimate(summaryProjection, Optional.empty()).tokens();
            if (summaryEstimate < descriptor.contextWindowTokens()) {
                return Optional.of(new CompactionBoundary(new SessionSequence(full.get(end).sequence().value()), List.copyOf(candidate),
                        List.copyOf(full.subList(end + 1, full.size()))));
            }
        }
        return Optional.empty();
    }

    private static long fourFifths(long value) {
        return (value / 5L) * 4L + (value % 5L) * 4L / 5L;
    }

    private static int completeBoundaryEnd(List<SessionMessage> history, int index) {
        SessionMessage message = history.get(index);
        if (!(message instanceof com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage calls)) {
            return index;
        }
        int end = index + calls.requests().size();
        if (end >= history.size()) {
            return -1;
        }
        for (int offset = 1; offset <= calls.requests().size(); offset++) {
            if (!(history.get(index + offset) instanceof com.java.system.sessionagent.conversation.domain.ToolObservation observation)) {
                return -1;
            }
            ToolRequest request = calls.requests().get(offset - 1);
            long expectedSequence;
            try {
                expectedSequence = Math.addExact(calls.sequence().value(), offset);
            } catch (ArithmeticException exception) {
                return -1;
            }
            if (!request.toolCallId().equals(observation.toolCallId())
                    || !request.toolName().value().equals(observation.toolName())
                    || !calls.sessionId().equals(observation.sessionId())
                    || !calls.messageJobId().equals(observation.messageJobId())
                    || observation.sequence().value() != expectedSequence) {
                return -1;
            }
        }
        return end;
    }

    private static boolean isResponseBoundary(SessionMessage message) {
        return message instanceof com.java.system.sessionagent.conversation.domain.AssistantMessage
                || message instanceof com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage
                || message instanceof com.java.system.sessionagent.conversation.domain.RuntimeMessage;
    }

    private static int indexAfter(List<SessionMessage> history, SessionSequence boundary) {
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).sequence().equals(boundary)) {
                return index + 1;
            }
        }
        return history.size();
    }

    private static List<SessionMessage> compactedSuffix(List<SessionMessage> history, Optional<ContextCompaction> compaction) {
        return compaction.map(value -> List.copyOf(history.subList(indexAfter(history, value.coveredThrough()), history.size()))).orElse(history);
    }

    private static Map<SessionSequence, ModelContinuation> continuationsAfter(
            Map<SessionSequence, ModelContinuation> continuations, Optional<ContextCompaction> compaction) {
        if (compaction.isEmpty()) {
            return continuations;
        }
        long boundary = compaction.orElseThrow().coveredThrough().value();
        return continuations.entrySet().stream().filter(entry -> entry.getKey().value() > boundary)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static boolean hasContinuationForAnotherRoute(
            Map<SessionSequence, ModelContinuation> continuations,
            ModelRouteId routeId) {
        return continuations.values().stream()
                .anyMatch(continuation -> !routeId.equals(continuation.modelRouteId()));
    }

    private int reserve(MessageWorkClaim claim, WorkGuard guard, ReservationState state) {
        if (!guard.stillOwned()) {
            throw new BudgetExhausted();
        }
        OptionalInt reserved = conversationStore.reserveModelCall(claim, maxModelCalls, clock.instant());
        if (reserved.isEmpty()) {
            throw new BudgetExhausted();
        }
        state.setOrdinal(reserved.getAsInt());
        return reserved.getAsInt();
    }

    private int reserveAndLogModelRequest(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState state,
            ModelDescriptor descriptor,
            int historySize,
            int toolCount) {
        int ordinal = reserve(claim, guard, state);
        logModelRequest(claim, descriptor, ordinal, historySize, toolCount);
        return ordinal;
    }

    private List<ConversationStore.MessageData> toolBatch(
            MessageWorkClaim claim,
            WorkGuard guard,
            int ordinal,
            ModelReply.UseTools reply,
            ToolSnapshot tools) {
        List<ConversationStore.MessageData> messages = new ArrayList<>();
        List<ConversationStore.ToolCallData> calls = reply.requests().stream()
                .map(request -> new ConversationStore.ToolCallData(request.toolCallId(), request.toolName().value(), request.arguments()))
                .toList();
        messages.add(new ConversationStore.AssistantToolCallsData(reply.message(), calls));
        for (ToolRequest request : reply.requests()) {
            if (!guard.stillOwned()) {
                return List.of();
            }
            messages.add(executeTool(claim, ordinal, tools, request));
        }
        return List.copyOf(messages);
    }

    private ConversationStore.ToolObservationData executeTool(
            MessageWorkClaim claim,
            int ordinal,
            ToolSnapshot tools,
            ToolRequest request) {
        long executionStartedAt = System.nanoTime();
        ToolOutput output;
        String outcome;
        try {
            output = tools.invoke(request.toolName(), request.arguments());
            outcome = output.isError() ? "FAILURE" : "SUCCESS";
        } catch (RuntimeException failure) {
            output = ToolOutput.runtimeFailure("TOOL_PROTOCOL_ERROR", "The tool could not be executed.");
            outcome = "FAILURE";
        }
        Duration duration = elapsedSince(executionStartedAt);
        telemetry.tool(request.toolName().value(), outcome, duration);
        logToolExecution(claim, ordinal, request, outcome, duration);
        return new ConversationStore.ToolObservationData(request.toolCallId(), request.toolName().value(), output.asStructuredValue());
    }

    private boolean handleModelFailure(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ModelCallFailure failure,
            Duration duration,
            ProcessingResultTracker resultTracker) {
        int ordinal = reservation.ordinal();
        logModelFailure(claim, ordinal, failure.kind(), duration);
        if (failure.kind() == ModelCallFailure.Kind.CORRECTABLE) {
            ConversationStore.JobUpdate update = ordinal < maxModelCalls
                    ? ConversationStore.JobUpdate.KEEP_WORKING : ConversationStore.JobUpdate.COMPLETE;
            return appendRuntime(claim, guard, List.of(runtime(MODEL_OUTPUT_INVALID)), update, resultTracker)
                    && update == ConversationStore.JobUpdate.KEEP_WORKING;
        }
        if (failure.kind() == ModelCallFailure.Kind.CONTEXT_TOO_LARGE) {
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        if (failure.kind() == ModelCallFailure.Kind.TRANSIENT && ordinal < maxModelCalls
                && scheduleRetry(claim, guard, resultTracker)) {
            return false;
        }
        appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)),
                ConversationStore.JobUpdate.COMPLETE, resultTracker);
        return false;
    }

    private boolean scheduleRetry(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
        if (job.isEmpty() || job.orElseThrow().retryCount() >= retryPolicy.transientRetries()) {
            return false;
        }
        Duration delay = retryDelay(job.orElseThrow().retryCount());
        boolean scheduled = conversationStore.scheduleRetry(claim, delay);
        if (scheduled) {
            resultTracker.retryScheduled();
            telemetry.retry("MODEL", delay);
            logRetry(claim, "MODEL", delay);
        } else {
            resultTracker.ownershipLost();
        }
        return scheduled;
    }

    private Duration retryDelay(int retryCount) {
        if (retryCount < 0 || retryCount >= Long.SIZE - 1) {
            return retryPolicy.maximumBackoff();
        }
        Duration delay = Duration.ofSeconds(1L << retryCount);
        return delay.compareTo(retryPolicy.maximumBackoff()) > 0 ? retryPolicy.maximumBackoff() : delay;
    }

    private boolean appendRuntime(
            MessageWorkClaim claim,
            WorkGuard guard,
            List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update,
            ProcessingResultTracker resultTracker) {
        return appendRuntime(claim, guard, messages, update, Optional.empty(), resultTracker);
    }

    private boolean appendRuntime(
            MessageWorkClaim claim,
            WorkGuard guard,
            List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update,
            Optional<ModelContinuation> continuation,
            ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        try {
            conversationStore.append(claim, new ConversationStore.MessageBatch(messages, update, continuation), clock.instant());
            if (update == ConversationStore.JobUpdate.COMPLETE) {
                resultTracker.completed();
            }
            for (ConversationStore.MessageData message : messages) {
                if (message instanceof ConversationStore.RuntimeData runtime) {
                    telemetry.feedback(runtime.code());
                }
            }
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
            return false;
        }
    }

    private boolean appendResponse(MessageWorkClaim claim, WorkGuard guard, List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update, Optional<ModelContinuation> continuation,
            Optional<ConversationStore.UsageCheckpointData> checkpoint,
            ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        try {
            conversationStore.append(claim, new ConversationStore.MessageBatch(messages, update, continuation, checkpoint), clock.instant());
            if (update == ConversationStore.JobUpdate.COMPLETE) {
                resultTracker.completed();
            }
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
            return false;
        }
    }

    private void recoverStorageFailure(
            MessageWorkClaim claim,
            WorkGuard guard,
            ConversationStoreFailure failure,
            ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return;
        }
        if (failure.kind() == ConversationStoreFailure.Kind.INVALID_HISTORY) {
            appendRuntime(claim, guard, List.of(runtime(INVALID_CONVERSATION_HISTORY)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return;
        }
        try {
            Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
            if (failure.kind() != ConversationStoreFailure.Kind.CONTRACT
                    && job.isPresent()
                    && job.orElseThrow().modelCallCount() < maxModelCalls
                    && job.orElseThrow().retryCount() < retryPolicy.transientRetries()) {
                Duration delay = retryDelay(job.orElseThrow().retryCount());
                boolean scheduled = conversationStore.scheduleRetry(claim, delay);
                if (scheduled) {
                    resultTracker.retryScheduled();
                    telemetry.retry("STORAGE", delay);
                    logRetry(claim, "STORAGE", delay);
                } else {
                    resultTracker.ownershipLost();
                }
                return;
            }
            appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)),
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
        } catch (RuntimeException ignored) {
            resultTracker.stateUnconfirmed();
        }
    }

    private static ConversationStore.RuntimeData runtime(String code) {
        return switch (code) {
            case MODEL_CALL_LIMIT_REACHED -> new ConversationStore.RuntimeData(code, "Runtime model call limit reached.");
            case MODEL_OUTPUT_INVALID -> new ConversationStore.RuntimeData(code, "Runtime model output is invalid.");
            case MODEL_UNAVAILABLE -> new ConversationStore.RuntimeData(code, "Runtime model is unavailable.");
            case CONTEXT_TOO_LARGE -> new ConversationStore.RuntimeData(code, "Runtime model context is too large.");
            case COMPACTION_FAILED -> new ConversationStore.RuntimeData(code, "Runtime context compaction failed.");
            case INVALID_CONVERSATION_HISTORY -> new ConversationStore.RuntimeData(code, "Runtime conversation history is invalid.");
            default -> throw new IllegalArgumentException("Unsupported runtime code");
        };
    }

    private static void logModelRequest(
            MessageWorkClaim claim,
            ModelDescriptor descriptor,
            int ordinal,
            int historySize,
            int toolCount) {
        LoggingEventBuilder event = LOGGER.atInfo().addKeyValue("event", "model_request")
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("callOrdinal", ordinal)
                .addKeyValue("historyCount", historySize)
                .addKeyValue("visibleToolCount", toolCount);
        if (Objects.nonNull(descriptor)) {
            event.addKeyValue("modelRouteId", descriptor.routeId().value())
                    .addKeyValue("modelId", descriptor.modelId());
        }
        event.log("runtime_lifecycle");
    }

    private static void logModelResponse(
            MessageWorkClaim claim,
            ModelDescriptor descriptor,
            int ordinal,
            ModelReply reply,
            ModelUsage usage,
            Duration duration) {
        String category = reply instanceof ModelReply.Text ? "ASSISTANT_TEXT" : "USE_TOOLS";
        LoggingEventBuilder event = LOGGER.atInfo().addKeyValue("event", "model_response")
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("callOrdinal", ordinal)
                .addKeyValue("outcome", category)
                .addKeyValue("durationMs", duration.toMillis());
        if (Objects.nonNull(descriptor)) {
            event.addKeyValue("modelRouteId", descriptor.routeId().value())
                    .addKeyValue("modelId", descriptor.modelId());
        }
        if (usage.available()) {
            event.addKeyValue("promptTokens", usage.promptTokens())
                    .addKeyValue("completionTokens", usage.completionTokens())
                    .addKeyValue("totalTokens", usage.totalTokens());
        }
        event.log("runtime_lifecycle");
    }

    private static void logModelFailure(MessageWorkClaim claim, int ordinal, ModelCallFailure.Kind kind, Duration duration) {
        LOGGER.atInfo().addKeyValue("event", "model_failure")
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("callOrdinal", ordinal)
                .addKeyValue("failureCategory", failureCategory(kind))
                .addKeyValue("durationMs", duration.toMillis()).log("runtime_lifecycle");
    }

    private static void logToolExecution(
            MessageWorkClaim claim,
            int ordinal,
            ToolRequest request,
            String outcome,
            Duration duration) {
        LOGGER.atInfo().addKeyValue("event", "tool_execution")
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("callOrdinal", ordinal)
                .addKeyValue("toolName", request.toolName().value())
                .addKeyValue("toolCallId", request.toolCallId().value())
                .addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", duration.toMillis()).log("runtime_lifecycle");
    }

    private static void logRetry(MessageWorkClaim claim, String category, Duration delay) {
        LOGGER.atInfo().addKeyValue("event", "message_job_retry")
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("failureCategory", category)
                .addKeyValue("retryDelayMs", delay.toMillis()).log("runtime_lifecycle");
    }

    private static void logCompaction(MessageWorkClaim claim, String event, String outcome, long estimate, long capacity) {
        LOGGER.atInfo().addKeyValue("event", event)
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("outcome", outcome)
                .addKeyValue("totalTokens", estimate)
                .addKeyValue("contextCapacityTokens", capacity)
                .addKeyValue("contextUsageRatio", capacity == 0L ? 0.0D : (double) estimate / capacity).log("runtime_lifecycle");
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static String failureCategory(ModelCallFailure.Kind kind) {
        return switch (kind) {
            case CORRECTABLE -> "OUTPUT_INVALID";
            case CONTEXT_TOO_LARGE -> "CONTEXT_TOO_LARGE";
            case TRANSIENT, TERMINAL -> "UNAVAILABLE";
            case INVALID_HISTORY -> "INVALID_HISTORY";
        };
    }

    private static final class BudgetExhausted extends RuntimeException {
    }

    private record ContextState(
            ModelRequest request,
            String requestShapeFingerprint,
            Optional<ContextCompaction> compaction,
            long estimateTokens,
            boolean requiresCompaction,
            Optional<ContextUsageProjection> projection) {
    }

    private record CompactionBoundary(SessionSequence boundary, List<SessionMessage> history, List<SessionMessage> suffix) {
    }

    private static final class ProcessingResultTracker {

        private Optional<MessageJobProcessingResult> result = Optional.empty();

        private void completed() {
            result = Optional.of(MessageJobProcessingResult.COMPLETED);
        }

        private void retryScheduled() {
            result = Optional.of(MessageJobProcessingResult.RETRY_SCHEDULED);
        }

        private void ownershipLost() {
            if (result.isEmpty()) {
                result = Optional.of(MessageJobProcessingResult.OWNERSHIP_LOST);
            }
        }

        private void stateUnconfirmed() {
            if (result.isEmpty()) {
                result = Optional.of(MessageJobProcessingResult.STATE_UNCONFIRMED);
            }
        }

        private MessageJobProcessingResult result(WorkGuard guard) {
            if (result.isPresent()) {
                return result.orElseThrow();
            }
            return guard.stillOwned()
                    ? MessageJobProcessingResult.STATE_UNCONFIRMED
                    : MessageJobProcessingResult.OWNERSHIP_LOST;
        }
    }

    private static final class ReservationState {
        private int ordinal;

        private void setOrdinal(int ordinal) {
            this.ordinal = ordinal;
        }

        private int ordinal() {
            return ordinal;
        }
    }
}
