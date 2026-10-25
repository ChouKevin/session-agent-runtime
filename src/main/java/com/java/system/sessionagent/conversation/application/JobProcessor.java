package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.tool.port.ToolCapability;
import java.util.Set;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import java.util.UUID;
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

import static com.java.system.sessionagent.conversation.domain.JobFailure.COMPACTION_FAILED;
import static com.java.system.sessionagent.conversation.domain.JobFailure.CONTEXT_TOO_LARGE;
import static com.java.system.sessionagent.conversation.domain.JobFailure.INVALID_CONVERSATION_HISTORY;
import static com.java.system.sessionagent.conversation.domain.JobFailure.MODEL_OUTPUT_INVALID;
import static com.java.system.sessionagent.conversation.domain.JobFailure.MODEL_UNAVAILABLE;

public final class JobProcessor implements MessageJobPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobProcessor.class);
    private static final Set<ToolCapability> NORMAL_CAPABILITIES = Set.of(ToolCapability.ORDINARY, ToolCapability.REPORT_GENERATION);
    private static final Set<ToolCapability> REPORT_CAPABILITIES = Set.of(ToolCapability.REPORT_GENERATION);

    private final ConversationStore conversationStore;
    private final ConversationModels conversationModels;
    private final ToolCatalog toolCatalog;
    private final SkillLibrary skillLibrary;
    private final Clock clock;
    private final int maxModelCalls;
    private final MessageJobRetryPolicy retryPolicy;
    private final ConversationTelemetry telemetry;
    private final ContextUsageEstimator contextUsageEstimator = new ContextUsageEstimator();
    private final JobHandoffCoordinator handoff;

    public JobProcessor(
            ConversationStore conversationStore,
            ConversationModels conversationModels,
            ToolCatalog toolCatalog,
            SkillLibrary skillLibrary,
            Clock clock,
            int maxModelCalls,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModels = Objects.requireNonNull(conversationModels, "Conversation models must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "Tool catalog must not be null");
        this.skillLibrary = Objects.requireNonNull(skillLibrary, "Skill library must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        Assert.isTrue(maxModelCalls > 0, "Maximum model calls must be positive");
        this.maxModelCalls = maxModelCalls;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "Message job retry policy must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
        this.handoff = new JobHandoffCoordinator(conversationStore, clock, maxModelCalls);
    }

    @Override
    public MessageJobProcessingResult process(MessageWorkClaim claim, WorkGuard workGuard) {
        Assert.notNull(claim, "Message work claim must not be null");
        Assert.notNull(workGuard, "Work guard must not be null");
        ProcessingResultTracker resultTracker = new ProcessingResultTracker();
        try {
            try {
                processClaim(claim, workGuard, resultTracker);
            } catch (ConversationStoreFailure failure) {
                recoverStorageFailure(claim, workGuard, failure, resultTracker);
            }
        } catch (JobCancellationRequestedException exception) {
            finalizeCancellation(claim, workGuard, resultTracker);
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
        }
        return resultTracker.result(workGuard);
    }

    private void processClaim(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker resultTracker) {
        checkCancellation(claim);
        Optional<ConversationModel> resolvedModel = conversationModels.resolve(claim.modelSelection());
        if (resolvedModel.isEmpty()) {
            appendRuntime(claim, guard, MODEL_UNAVAILABLE, ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return;
        }
        ConversationModel conversationModel = resolvedModel.orElseThrow();
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
            appendRuntime(claim, guard, MODEL_UNAVAILABLE, ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return;
        }
        while (guard.stillOwned()) {
            checkCancellation(claim);
            ConversationHistory history = conversationStore.loadContextHistory(claim.sessionId());
            SkillCommandState command = history.commands().get(claim.messageJobId());
            if (!prepareSkillCommand(claim, guard, resultTracker, command)) {
                return;
            }
            if (Objects.nonNull(command) && command.disposition() == SkillCommandState.Disposition.PENDING) {
                history = conversationStore.loadContextHistory(claim.sessionId());
            }
            JobHandoffCoordinator.Decision decision = handoff.prepare(claim, guard, conversationModel, history);
            if (decision.waitingResponse().isPresent()) {
                appendWaiting(claim, guard, decision.waitingResponse().orElseThrow(), resultTracker);
                return;
            }
            JobControl control = decision.control();
            if (control.stage() == JobControl.Stage.WRAP_UP || control.ordinaryCallCount() >= maxModelCalls) {
                wrapUp(claim, guard, conversationModel, resultTracker);
                return;
            }
            ContextHistoryView historyView = new ContextHistoryView(history);
            Map<SessionSequence, ModelContinuation> continuations = Optional.ofNullable(conversationStore.loadContinuations(claim))
                    .orElseGet(Map::of);
            if (hasContinuationForAnotherRoute(continuations, routeId)) {
                appendRuntime(claim, guard, MODEL_UNAVAILABLE, ConversationStore.JobUpdate.COMPLETE, resultTracker);
                return;
            }
            Set<ToolCapability> allowed = control.mode() == JobControl.Mode.REPORT_ONLY
                    ? REPORT_CAPABILITIES : NORMAL_CAPABILITIES;
            try (ToolSnapshot tools = toolCatalog.snapshot(claim.sessionId().value(), claim.messageJobId().value()).restrictedTo(allowed)) {
                ReservationState reservation = new ReservationState();
                ContextState context = contextState(claim, historyView, continuations, tools, activeModelDescriptor, conversationModel);
                if (context.requiresCompaction()) {
                    if (!compact(claim, guard, reservation, context, ContextCompaction.Reason.THRESHOLD, resultTracker, conversationModel)) {
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
                checkCancellation(claim);
                try {
                    result = conversationModel.respond(request, () -> reserveAndLogModelRequest(claim, guard, reservation,
                            activeModelDescriptor, request.history().size(), tools.definitions().size()), usage -> { });
                    checkCancellation(claim);
                    reply = result.reply();
                    continuation = result.continuation();
                } catch (BudgetExhausted exception) {
                    wrapUp(claim, guard, conversationModel, resultTracker);
                    return;
                } catch (ModelCallFailure failure) {
                    checkCancellation(claim);
                    if (failure.kind() == ModelCallFailure.Kind.INVALID_HISTORY) {
                        logModelFailure(claim, reservation.ordinal(), failure.kind(), elapsedSince(requestStartedAt));
                        appendRuntime(claim, guard, INVALID_CONVERSATION_HISTORY,
                                ConversationStore.JobUpdate.COMPLETE, resultTracker);
                        return;
                    }
                    if (reservation.executionOrdinal() >= maxModelCalls) {
                        wrapUp(claim, guard, conversationModel, resultTracker);
                        return;
                    }
                    if (failure.kind() == ModelCallFailure.Kind.CONTEXT_TOO_LARGE
                            && recoverOverflow(claim, guard, reservation, context, elapsedSince(requestStartedAt), resultTracker, conversationModel)) {
                        continue;
                    }
                    if (handleModelFailure(claim, guard, reservation, failure,
                            elapsedSince(requestStartedAt), resultTracker)) {
                        continue;
                    }
                    return;
                }
                if (continuation.isPresent() && !routeId.equals(continuation.orElseThrow().modelRouteId())) {
                    appendRuntime(claim, guard, MODEL_UNAVAILABLE,
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
                            ConversationStore.JobUpdate.COMPLETE, Optional.empty(), checkpoint(result, claim.modelSelection(), activeModelDescriptor, ordinal,
                                    requestShapeFingerprint, context.compaction().map(value -> value.generation()).orElse(0L),
                                    history.messages()), resultTracker);
                    return;
                }
                ModelReply.UseTools useTools = (ModelReply.UseTools) reply;
                if (reservation.executionOrdinal() >= maxModelCalls) {
                    wrapUp(claim, guard, conversationModel, resultTracker);
                    return;
                }
                Optional<ConversationStore.UsageCheckpointData> usageCheckpoint = checkpoint(result, claim.modelSelection(),
                        activeModelDescriptor, ordinal, requestShapeFingerprint,
                        context.compaction().map(value -> value.generation()).orElse(0L), history.messages());
                List<ConversationStore.MessageData> messages = toolBatch(claim, guard, ordinal, useTools, tools,
                        continuation, usageCheckpoint, resultTracker);
                if (!guard.stillOwned()) {
                    return;
                }
                if (!appendResponse(claim, guard, messages, ConversationStore.JobUpdate.KEEP_WORKING, continuation,
                        usageCheckpoint, resultTracker)) {
                    return;
                }
            }
        }
    }

    private boolean prepareSkillCommand(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker tracker,
            SkillCommandState command) {
        if (Objects.isNull(command)) {
            return true;
        }
        if (command.disposition() == SkillCommandState.Disposition.REJECTED) {
            tracker.committed(JobStatus.DONE);
            return false;
        }
        if (command.disposition() == SkillCommandState.Disposition.ACCEPTED) {
            return true;
        }
        checkCancellation(claim);
        if (!guard.confirmOwnership()) {
            tracker.ownershipLost();
            return false;
        }
        conversationStore.startSkillCommand(claim);
        SkillReadResult read = skillLibrary.metadata().stream()
                .filter(metadata -> metadata.name().equals(command.command().requestedName()))
                .findFirst()
                .<SkillReadResult>map(metadata -> skillLibrary.read(metadata.uri()))
                .orElseGet(() -> new SkillReadResult.Failure("SKILL_UNAVAILABLE"));
        checkCancellation(claim);
        if (!guard.confirmOwnership()) {
            tracker.ownershipLost();
            return false;
        }
        tracker.appendOutcomeUncertain = true;
        JobStatus status = conversationStore.resolveSkillCommand(claim, read, clock.instant());
        tracker.committed(status);
        return !status.isTerminal() && guard.stillOwned();
    }

    private Optional<ConversationStore.UsageCheckpointData> checkpoint(
            ModelCallResult result,
            ModelSelection selection,
            ModelDescriptor descriptor,
            int ordinal,
            String requestShapeFingerprint, long compactGeneration, List<SessionMessage> auditHistory) {
        if (!result.usage().available() || Objects.isNull(descriptor) || !org.springframework.util.StringUtils.hasText(requestShapeFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new ConversationStore.UsageCheckpointData(selection, descriptor, ordinal, result.usage().promptTokens(),
                result.usage().completionTokens(), result.usage().totalTokens(), requestShapeFingerprint, compactGeneration,
                auditHistory.getLast().sequence()));
    }

    private ContextState contextState(
            MessageWorkClaim claim,
            ContextHistoryView history,
            Map<SessionSequence, ModelContinuation> continuations,
            ToolSnapshot tools,
            ModelDescriptor descriptor,
            ConversationModel conversationModel) {
        if (Objects.isNull(descriptor) || "unspecified".equals(descriptor.modelId())
                || !org.springframework.util.StringUtils.hasText(conversationModel.systemPrompt())) {
            return new ContextState(new ModelRequest(history.between(0, Long.MAX_VALUE), continuations, tools, ModelRequest.Purpose.ANSWER), "", Optional.empty(), 0, false, Optional.empty());
        }
        Optional<ContextCompaction> compaction = conversationStore.loadCompaction(claim.sessionId());
        List<SessionMessage> visibleHistory = history.between(compaction.map(value -> value.coveredThrough().value()).orElse(0L), Long.MAX_VALUE);
        Map<SessionSequence, ModelContinuation> visibleContinuations = continuationsAfter(continuations, compaction);
        Optional<ContextSummary> summary = compaction.map(value -> value.summary());
        long generation = compaction.map(value -> value.generation()).orElse(0L);
        ContextUsageProjection projection = new ContextUsageProjection(descriptor,
                conversationModel.systemPrompt() + ModelRequest.answerDirective(tools), tools.definitions(),
                visibleHistory, generation, summary, contextUsageEstimator.eligibilityFingerprint(history.eligibilityFingerprint(), tools));
        String fingerprint = contextUsageEstimator.requestShapeFingerprint(projection);
        long estimate = contextUsageEstimator.estimate(projection,
                conversationStore.loadUsageCheckpoint(claim.sessionId(), claim.modelSelection(), descriptor, fingerprint, generation)).tokens();
        long threshold = fourFifths(descriptor.contextWindowTokens());
        if (estimate >= threshold) {
            logCompaction(claim, "compact_threshold_reached", "THRESHOLD_REACHED", estimate, descriptor.contextWindowTokens());
        }
        return new ContextState(new ModelRequest(visibleHistory, visibleContinuations, tools, summary, ModelRequest.Purpose.ANSWER), fingerprint, compaction,
                estimate, estimate >= threshold, Optional.of(projection));
    }

    private boolean recoverOverflow(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ContextState context,
            Duration duration,
            ProcessingResultTracker resultTracker,
            ConversationModel conversationModel) {
        logModelFailure(claim, reservation.ordinal(), ModelCallFailure.Kind.CONTEXT_TOO_LARGE, duration);
        if (conversationStore.hasOverflowCompaction(claim.messageJobId())) {
            appendRuntime(claim, guard, CONTEXT_TOO_LARGE,
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        return compact(claim, guard, new ReservationState(), context, ContextCompaction.Reason.OVERFLOW, resultTracker, conversationModel);
    }

    private boolean compact(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ContextState context,
            ContextCompaction.Reason reason,
            ProcessingResultTracker resultTracker,
            ConversationModel conversationModel) {
        checkCancellation(claim);
        if (context.projection().isEmpty()) {
            logCompaction(claim, "compact_failure", "NO_PROJECTION", context.estimateTokens(), 0L);
            appendRuntime(claim, guard, CONTEXT_TOO_LARGE,
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        ContextUsageProjection projection = context.projection().orElseThrow();
        Optional<CompactionBoundary> boundary = selectBoundary(context, projection.model(), conversationModel);
        if (boundary.isEmpty()) {
            logCompaction(claim, "compact_failure", "NO_BOUNDARY", context.estimateTokens(), projection.model().contextWindowTokens());
            appendRuntime(claim, guard, CONTEXT_TOO_LARGE,
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        CompactionBoundary selected = boundary.orElseThrow();
        logCompaction(claim, "compact_started", reason.name(), context.estimateTokens(), projection.model().contextWindowTokens());
        String summary;
        reservation.purpose = ModelCallPurpose.COMPACTION;
        try {
            summary = conversationModel.summarize(new ContextCompactionRequest(context.compaction().map(value -> value.summary()),
                    selected.history()), () -> reserveAndLogModelRequest(claim, guard, reservation, projection.model(),
                    selected.history().size(), 0));
            checkCancellation(claim);
        } catch (BudgetExhausted exception) {
            logCompaction(claim, "compact_failure", "MODEL_LIMIT", context.estimateTokens(), projection.model().contextWindowTokens());
            wrapUp(claim, guard, conversationModel, resultTracker);
            return false;
        } catch (ModelCallFailure failure) {
            checkCancellation(claim);
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
            if (reservation.executionOrdinal() >= maxModelCalls) {
                wrapUp(claim, guard, conversationModel, resultTracker);
            } else {
                appendRuntime(claim, guard, COMPACTION_FAILED, ConversationStore.JobUpdate.COMPLETE, resultTracker);
            }
            return false;
        }
        ContextUsageProjection afterProjection = new ContextUsageProjection(projection.model(), projection.systemPrompt(),
                projection.toolDefinitions(), selected.suffix(), context.compaction().map(value -> value.generation()).orElse(0L) + 1,
                Optional.of(compactedSummary), projection.eligibilityFingerprint());
        long afterEstimate = contextUsageEstimator.estimate(afterProjection, Optional.empty()).tokens();
        if (afterEstimate >= fourFifths(projection.model().contextWindowTokens())) {
            logCompaction(claim, "compact_failure", "SUMMARY_TOO_LARGE", afterEstimate, projection.model().contextWindowTokens());
            if (reservation.executionOrdinal() >= maxModelCalls) {
                wrapUp(claim, guard, conversationModel, resultTracker);
            } else {
                appendRuntime(claim, guard, CONTEXT_TOO_LARGE, ConversationStore.JobUpdate.COMPLETE, resultTracker);
            }
            return false;
        }
        try {
            conversationStore.updateProgress(claim, new JobProgress(JobPhase.HISTORY_COMMIT, clock.instant(), Optional.empty()));
            conversationStore.compact(claim, new ConversationStore.CompactionData(
                    context.compaction().map(value -> value.generation()).orElse(0L) + 1, reason, compactedSummary.text(),
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
        if (reservation.executionOrdinal() >= maxModelCalls) {
            wrapUp(claim, guard, conversationModels.resolve(claim.modelSelection()).orElseThrow(), resultTracker);
            return false;
        }
        if (failure.kind() == ModelCallFailure.Kind.TRANSIENT && reservation.executionOrdinal() < maxModelCalls
                && scheduleRetry(claim, guard, resultTracker)) {
            return false;
        }
        appendRuntime(claim, guard, COMPACTION_FAILED,
                ConversationStore.JobUpdate.COMPLETE, resultTracker);
        return false;
    }

    private Optional<CompactionBoundary> selectBoundary(
            ContextState context,
            ModelDescriptor descriptor,
            ConversationModel conversationModel) {
        ContextUsageProjection projection = context.projection().orElseThrow();
        List<SessionMessage> full = projection.history();
        for (int index = full.size() - 1; index >= 0; index--) {
            int end = completeBoundaryEnd(full, index);
            if (end < index || !isResponseBoundary(full.get(index))) {
                continue;
            }
            List<SessionMessage> candidate = full.subList(0, end + 1);
            ContextUsageProjection summaryProjection = new ContextUsageProjection(descriptor, conversationModel.compactionPrompt(), List.of(), candidate,
                    0, context.compaction().map(value -> value.summary()), projection.eligibilityFingerprint());
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


    private static Map<SessionSequence, ModelContinuation> continuationsAfter(
            Map<SessionSequence, ModelContinuation> continuations, Optional<ContextCompaction> compaction) {
        if (compaction.isEmpty()) {
            return continuations;
        }
        long boundary = compaction.orElseThrow().coveredThrough().value();
        return continuations.entrySet().stream().filter(entry -> entry.getKey().value() > boundary)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(entry -> entry.getKey(), entry -> entry.getValue()));
    }

    private static boolean hasContinuationForAnotherRoute(
            Map<SessionSequence, ModelContinuation> continuations,
            ModelRouteId routeId) {
        return continuations.values().stream()
                .anyMatch(continuation -> !routeId.equals(continuation.modelRouteId()));
    }

    private int reserve(MessageWorkClaim claim, WorkGuard guard, ReservationState state) {
        if (!guard.stillOwned()) {
            throw new StaleWorkClaimException();
        }
        checkCancellation(claim);
        ModelCallReservationResult result = conversationStore.reserveModelCall(claim, state.purpose, maxModelCalls, clock.instant());
        if (!(result instanceof ModelCallReservationResult.Reserved reserved)) {
            throw new BudgetExhausted();
        }
        if (!guard.stillOwned()) {
            throw new StaleWorkClaimException();
        }
        state.setOrdinal(reserved.totalOrdinal());
        state.executionOrdinal = reserved.executionOrdinal();
        return reserved.totalOrdinal();
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
            ToolSnapshot tools,
            Optional<ModelContinuation> continuation,
            Optional<ConversationStore.UsageCheckpointData> checkpoint,
            ProcessingResultTracker resultTracker) {
        List<ConversationStore.MessageData> messages = new ArrayList<>();
        List<ConversationStore.ToolCallData> calls = reply.requests().stream()
                .map(request -> new ConversationStore.ToolCallData(request.toolCallId(), request.toolName().value(), request.arguments()))
                .toList();
        messages.add(new ConversationStore.AssistantToolCallsData(reply.message(), calls));
        // Keep attempt-local observations reachable if a storage safe point unwinds this batch.
        resultTracker.pendingToolBatch = Optional.of(new PendingToolBatch(messages, continuation, checkpoint));
        boolean cancelled = false;
        int toolOrdinal = 0;
        for (ToolRequest request : reply.requests()) {
            if (!guard.confirmOwnership()) {
                return List.of();
            }
            if (!cancelled) {
                try {
                    checkCancellation(claim);
                    conversationStore.updateProgress(claim,
                            new JobProgress(JobPhase.TOOL_EXECUTION, clock.instant(), Optional.of(request.toolName().value())));
                } catch (JobCancellationRequestedException exception) {
                    cancelled = true;
                }
            }
            if (!guard.stillOwned()) {
                return List.of();
            }
            messages.add(cancelled
                    ? new ConversationStore.ToolObservationData(request.toolCallId(), request.toolName().value(),
                            ToolOutput.runtimeFailure(JobCancellation.TOOL_CODE, JobCancellation.TOOL_MESSAGE).asStructuredValue())
                    : executeTool(claim, ordinal, toolOrdinal, tools, request));
            toolOrdinal++;
        }
        return List.copyOf(messages);
    }

    private ConversationStore.ToolObservationData executeTool(
            MessageWorkClaim claim,
            int ordinal,
            int toolOrdinal,
            ToolSnapshot tools,
            ToolRequest request) {
        long executionStartedAt = System.nanoTime();
        ToolOutput output;
        String outcome;
        try {
            ToolInvocation invocation = new ToolInvocation(new ToolExecutionContext(UUID.fromString(claim.sessionId().value()),
                    UUID.fromString(claim.messageJobId().value()), ordinal, toolOrdinal),
                    new ToolExecutionAuthority(claim.workerId(), claim.claimNumber()));
            output = tools.invoke(invocation, request.toolName(), request.arguments());
            outcome = output.isError() ? "FAILURE" : "SUCCESS";
        } catch (StaleWorkClaimException | JobCancellationRequestedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            output = ToolOutput.runtimeFailure("TOOL_PROTOCOL_ERROR", "The tool could not be executed.");
            outcome = "FAILURE";
        }
        Duration duration = elapsedSince(executionStartedAt);
        telemetry.tool(request.toolName().value(), outcome, duration);
        logToolExecution(claim, ordinal, request, outcome, duration);
        return new ConversationStore.ToolObservationData(request.toolCallId(), request.toolName().value(),
                output.asStructuredValue(), output.artifactReceipt(), output.skillReadReceipt());
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
        if (reservation.executionOrdinal() >= maxModelCalls) {
            wrapUp(claim, guard, conversationModels.resolve(claim.modelSelection()).orElseThrow(), resultTracker);
            return false;
        }
        if (failure.kind() == ModelCallFailure.Kind.CORRECTABLE) {
            ConversationStore.JobUpdate update = reservation.executionOrdinal() < maxModelCalls
                    ? ConversationStore.JobUpdate.KEEP_WORKING : ConversationStore.JobUpdate.COMPLETE;
            return appendRuntime(claim, guard, MODEL_OUTPUT_INVALID, update, resultTracker)
                    && update == ConversationStore.JobUpdate.KEEP_WORKING;
        }
        if (failure.kind() == ModelCallFailure.Kind.CONTEXT_TOO_LARGE) {
            appendRuntime(claim, guard, CONTEXT_TOO_LARGE,
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
            return false;
        }
        if (failure.kind() == ModelCallFailure.Kind.TRANSIENT && reservation.executionOrdinal() < maxModelCalls
                && scheduleRetry(claim, guard, resultTracker)) {
            return false;
        }
        appendRuntime(claim, guard, MODEL_UNAVAILABLE,
                ConversationStore.JobUpdate.COMPLETE, resultTracker);
        return false;
    }

    private boolean scheduleRetry(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        checkCancellation(claim);
        Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
        if (job.isEmpty() || job.orElseThrow().retryCount() >= retryPolicy.transientRetries()) {
            return false;
        }
        Duration delay = retryDelay(job.orElseThrow().retryCount());
        boolean scheduled = conversationStore.scheduleRetry(claim, delay, MODEL_UNAVAILABLE);
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
            JobFailure failure,
            ConversationStore.JobUpdate update,
            ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        checkCancellation(claim);
        try {
            conversationStore.updateProgress(claim, new JobProgress(JobPhase.HISTORY_COMMIT, clock.instant(), Optional.empty()));
            ConversationStore.RuntimeData message = new ConversationStore.RuntimeData(failure.name(), failure.message());
            Optional<PendingToolBatch> pending = resultTracker.pendingToolBatch;
            List<ConversationStore.MessageData> messages;
            if (pending.isPresent()) {
                List<ConversationStore.MessageData> observations = pending.orElseThrow().messages();
                messages = new ArrayList<>(observations.size() + 1);
                messages.addAll(observations);
                messages.add(message);
            } else {
                messages = List.of(message);
            }
            ConversationStore.MessageBatch batch = new ConversationStore.MessageBatch(messages, update,
                    Optional.empty(), pending.flatMap(PendingToolBatch::checkpoint), Optional.of(failure), ConversationStore.CompletionDisposition.NONE);
            resultTracker.appendOutcomeUncertain = true;
            JobStatus status = conversationStore.append(claim, batch, clock.instant());
            resultTracker.committed(status);
            if (status != JobStatus.CANCELLED) {
                telemetry.feedback(failure.name());
            }
            return !status.isTerminal() && guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
            return false;
        }
    }

    private boolean canRetryStorage(MessageWorkClaim claim) {
        JobControl control = conversationStore.loadJobControl(claim);
        return control.ordinaryCallCount() < maxModelCalls || control.stage() == JobControl.Stage.WRAP_UP;
    }

    private void wrapUp(MessageWorkClaim claim, WorkGuard guard, ConversationModel model, ProcessingResultTracker tracker) {
        String text = handoff.wrap(claim, guard, model);
        appendWaiting(claim, guard, text, tracker);
    }

    private void appendWaiting(MessageWorkClaim claim, WorkGuard guard, String text, ProcessingResultTracker tracker) {
        appendResponse(claim, guard, List.of(new ConversationStore.AssistantData(text)), ConversationStore.JobUpdate.COMPLETE,
                Optional.empty(), Optional.empty(), ConversationStore.CompletionDisposition.WAITING, tracker);
    }

    private boolean appendResponse(MessageWorkClaim claim, WorkGuard guard, List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update, Optional<ModelContinuation> continuation,
            Optional<ConversationStore.UsageCheckpointData> checkpoint,
            ProcessingResultTracker resultTracker) {
        return appendResponse(claim, guard, messages, update, continuation, checkpoint,
                update == ConversationStore.JobUpdate.COMPLETE ? ConversationStore.CompletionDisposition.ANSWER
                        : ConversationStore.CompletionDisposition.NONE, resultTracker);
    }

    private boolean appendResponse(MessageWorkClaim claim, WorkGuard guard, List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update, Optional<ModelContinuation> continuation,
            Optional<ConversationStore.UsageCheckpointData> checkpoint,
            ConversationStore.CompletionDisposition disposition, ProcessingResultTracker resultTracker) {
        if (!guard.stillOwned()) {
            resultTracker.ownershipLost();
            return false;
        }
        try {
            boolean cancelled = conversationStore.isCancellationRequested(claim);
            if (cancelled) {
                update = ConversationStore.JobUpdate.CANCEL;
                continuation = Optional.empty();
                if (!(messages.getFirst() instanceof ConversationStore.AssistantToolCallsData)) {
                    messages = cancellationMessages();
                    checkpoint = Optional.empty();
                }
                disposition = ConversationStore.CompletionDisposition.NONE;
            }
            conversationStore.updateProgress(claim, new JobProgress(JobPhase.HISTORY_COMMIT, clock.instant(), Optional.empty()));
            ConversationStore.MessageBatch batch = new ConversationStore.MessageBatch(messages, update, continuation, checkpoint,
                    Optional.empty(), disposition);
            resultTracker.appendOutcomeUncertain = true;
            JobStatus status = conversationStore.append(claim, batch, clock.instant());
            resultTracker.committed(status);
            return !status.isTerminal() && guard.stillOwned();
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
        if (resultTracker.appendOutcomeUncertain) {
            // An append may have committed before its response failed. Never resend or replace that batch.
            resultTracker.stateUnconfirmed();
            return;
        }
        // Storage stopped admission before cancellation was observed; preserve that actual stop reason.
        resultTracker.pendingToolBatch.ifPresent(PendingToolBatch::stopForStorageFailure);
        try {
            checkCancellation(claim);
            if (failure.kind() == ConversationStoreFailure.Kind.INVALID_HISTORY) {
                appendRuntime(claim, guard, INVALID_CONVERSATION_HISTORY,
                        ConversationStore.JobUpdate.COMPLETE, resultTracker);
                return;
            }
            Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
            if (failure.kind() != ConversationStoreFailure.Kind.CONTRACT
                    && job.isPresent()
                    && canRetryStorage(claim)
                    && job.orElseThrow().retryCount() < retryPolicy.transientRetries()) {
                Duration delay = retryDelay(job.orElseThrow().retryCount());
                boolean scheduled;
                if (resultTracker.pendingToolBatch.isPresent()) {
                    PendingToolBatch pending = resultTracker.pendingToolBatch.orElseThrow();
                    ConversationStore.MessageBatch batch = new ConversationStore.MessageBatch(
                            pending.messages(), ConversationStore.JobUpdate.KEEP_WORKING, pending.continuation(),
                            pending.checkpoint(), Optional.empty(), ConversationStore.CompletionDisposition.NONE);
                    resultTracker.appendOutcomeUncertain = true;
                    JobStatus status = conversationStore.appendAndScheduleRetry(
                            claim, batch, delay, JobFailure.STORAGE_UNAVAILABLE, clock.instant());
                    resultTracker.committed(status);
                    if (status == JobStatus.CANCELLED) {
                        return;
                    }
                    scheduled = status == JobStatus.RETRY;
                } else {
                    scheduled = conversationStore.scheduleRetry(claim, delay, JobFailure.STORAGE_UNAVAILABLE);
                }
                if (scheduled) {
                    resultTracker.retryScheduled();
                    telemetry.retry("STORAGE", delay);
                    logRetry(claim, "STORAGE", delay);
                } else {
                    resultTracker.ownershipLost();
                }
                return;
            }
            appendRuntime(claim, guard, JobFailure.STORAGE_UNAVAILABLE,
                    ConversationStore.JobUpdate.COMPLETE, resultTracker);
        } catch (JobCancellationRequestedException exception) {
            throw exception;
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
        } catch (RuntimeException ignored) {
            resultTracker.stateUnconfirmed();
        }
    }

    private void checkCancellation(MessageWorkClaim claim) {
        if (conversationStore.isCancellationRequested(claim)) {
            throw new JobCancellationRequestedException();
        }
    }

    private static List<ConversationStore.MessageData> cancellationMessages() {
        return List.of(new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE));
    }

    private void finalizeCancellation(MessageWorkClaim claim, WorkGuard guard, ProcessingResultTracker resultTracker) {
        try {
            Optional<PendingToolBatch> pending = resultTracker.pendingToolBatch;
            List<ConversationStore.MessageData> messages = pending.map(PendingToolBatch::cancelUnstarted).orElseGet(JobProcessor::cancellationMessages);
            appendResponse(claim, guard, messages, ConversationStore.JobUpdate.CANCEL,
                    Optional.empty(), pending.flatMap(PendingToolBatch::checkpoint), resultTracker);
        } catch (StaleWorkClaimException exception) {
            resultTracker.ownershipLost();
        } catch (ConversationStoreFailure failure) {
            resultTracker.stateUnconfirmed();
        }
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

    private record PendingToolBatch(
            List<ConversationStore.MessageData> messages,
            Optional<ModelContinuation> continuation,
            Optional<ConversationStore.UsageCheckpointData> checkpoint) {

        private List<ConversationStore.MessageData> cancelUnstarted() {
            markUnstarted(JobCancellation.TOOL_CODE, JobCancellation.TOOL_MESSAGE);
            return List.copyOf(messages);
        }

        private void stopForStorageFailure() {
            markUnstarted("TOOL_NOT_EXECUTED", "Tool was not executed because Runtime storage failed.");
        }

        private void markUnstarted(String code, String message) {
            ConversationStore.AssistantToolCallsData assistant = (ConversationStore.AssistantToolCallsData) messages.getFirst();
            for (int index = messages.size() - 1; index < assistant.calls().size(); index++) {
                ConversationStore.ToolCallData call = assistant.calls().get(index);
                messages.add(new ConversationStore.ToolObservationData(call.toolCallId(), call.toolName(),
                        ToolOutput.runtimeFailure(code, message).asStructuredValue()));
            }
        }
    }

    private static final class ProcessingResultTracker {

        private Optional<MessageJobProcessingResult> result = Optional.empty();
        private Optional<PendingToolBatch> pendingToolBatch = Optional.empty();
        private boolean appendOutcomeUncertain;

        private void committed(JobStatus status) {
            appendOutcomeUncertain = false;
            pendingToolBatch = Optional.empty();
            if (status == JobStatus.CANCELLED) {
                result = Optional.of(MessageJobProcessingResult.CANCELLED);
            } else if (status == JobStatus.DONE) {
                result = Optional.of(MessageJobProcessingResult.COMPLETED);
            }
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
        private int executionOrdinal;
        private ModelCallPurpose purpose = ModelCallPurpose.EXECUTION;

        private int executionOrdinal() {
            return executionOrdinal;
        }

        private void setOrdinal(int ordinal) {
            this.ordinal = ordinal;
        }

        private int ordinal() {
            return ordinal;
        }
    }
}
