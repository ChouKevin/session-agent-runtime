package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coordinates durable control requests, never ordinary model/tool execution. */
public final class JobHandoffCoordinator {
    public static final String WRAP_FALLBACK = "Investigation paused; existing records are saved. A progress summary is unavailable. "
            + "Would you like to continue investigating or organize the existing evidence with unknowns marked?";
    public static final String INTENT_FALLBACK = "Please clarify: would you like to continue investigating, "
            + "organize the existing evidence, or start a different question?";

    private final ConversationStore store;
    private final JobCacheUsageRecorder cacheUsageRecorder;
    private final Clock clock;
    private final int maxExecutionCalls;
    private final ContextUsageEstimator estimator = new ContextUsageEstimator();

    public JobHandoffCoordinator(ConversationStore store, Clock clock, int maxExecutionCalls) {
        this.store = store;
        this.cacheUsageRecorder = new JobCacheUsageRecorder(store);
        this.clock = clock;
        this.maxExecutionCalls = maxExecutionCalls;
    }

    public record Decision(JobControl control, Optional<String> waitingResponse) {}

    public Decision prepare(MessageWorkClaim claim, WorkGuard guard, ConversationModel model, ConversationHistory history) {
        JobControl control = store.loadJobControl(claim);
        if (control.stage() != JobControl.Stage.INTENT || control.waiting().isEmpty()) {
            return new Decision(control, Optional.empty());
        }
        if (control.intentAttempted()) {
            return new Decision(control, Optional.of(INTENT_FALLBACK));
        }
        ControlContext context = context(claim, history);
        IntentRequest request = new IntentRequest(control.initiatingMessage(), context.history(), context.summary(),
                control.waiting().orElseThrow());
        List<SessionMessage> estimatedHistory = new ArrayList<>(context.history());
        estimatedHistory.add(control.initiatingMessage());
        if (!fits(model, request.directive(), estimatedHistory, context.summary(), context.generation())) {
            return new Decision(control, Optional.of(INTENT_FALLBACK));
        }
        IntentResult result;
        try {
            result = model.classifyIntent(request, () -> reserve(claim, guard, ModelCallPurpose.INTENT),
                    observation -> cacheUsageRecorder.record(claim, observation));
        } catch (ModelCallFailure | AlreadyAttempted exception) {
            return new Decision(control, Optional.of(INTENT_FALLBACK));
        }
        checkOwnership(claim, guard);
        JobControl resolved = store.resolveFollowUp(claim, control.waiting().orElseThrow(), result.intent(), clock.instant());
        return new Decision(resolved, result.intent() == FollowUpIntent.CLARIFY ? result.clarification() : Optional.empty());
    }

    public String wrap(MessageWorkClaim claim, WorkGuard guard, ConversationModel model) {
        JobControl control = store.enterWrapUp(claim, clock.instant());
        if (control.wrapAttempted()) {
            return WRAP_FALLBACK;
        }
        ControlContext context = context(claim, store.loadContextHistory(claim.sessionId()));
        List<SessionMessage> wrapHistory = context.history();
        if (wrapHistory.stream().noneMatch(message -> message.sequence().equals(control.initiatingMessage().sequence()))) {
            ArrayList<SessionMessage> withInitiatingMessage = new ArrayList<>(wrapHistory);
            withInitiatingMessage.add(control.initiatingMessage());
            wrapHistory = List.copyOf(withInitiatingMessage);
        }
        Map<SessionSequence, ModelContinuation> storedContinuations = store.loadContinuations(claim);
        Map<SessionSequence, ModelContinuation> visibleContinuations = wrapHistory.stream()
                .filter(AssistantToolCallsMessage.class::isInstance)
                .map(SessionMessage::sequence)
                .filter(storedContinuations::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(sequence -> sequence, storedContinuations::get));
        if (visibleContinuations.values().stream()
                .anyMatch(continuation -> !model.routeId().equals(continuation.modelRouteId()))) {
            return WRAP_FALLBACK;
        }
        if (!fits(model, model.systemPrompt() + ModelRequest.directive(ModelRequest.Purpose.WRAP_UP),
                wrapHistory, context.summary(), context.generation())) {
            return WRAP_FALLBACK;
        }
        try (ToolSnapshot empty = new ToolSnapshot(List.of())) {
            ModelRequest request = new ModelRequest(wrapHistory, visibleContinuations, empty, context.summary(), ModelRequest.Purpose.WRAP_UP);
            try {
                ModelReply reply = model.respond(request, () -> reserve(claim, guard, ModelCallPurpose.WRAP_UP),
                        observation -> cacheUsageRecorder.record(claim, observation)).reply();
                checkOwnership(claim, guard);
                return reply instanceof ModelReply.Text text ? text.message() : WRAP_FALLBACK;
            } catch (ModelCallFailure | AlreadyAttempted exception) {
                return WRAP_FALLBACK;
            }
        }
    }

    private int reserve(MessageWorkClaim claim, WorkGuard guard, ModelCallPurpose purpose) {
        checkOwnership(claim, guard);
        ModelCallReservationResult result = store.reserveModelCall(claim, purpose, maxExecutionCalls, clock.instant());
        checkOwnership(claim, guard);
        if (result instanceof ModelCallReservationResult.Reserved reserved) {
            return reserved.totalOrdinal();
        }
        throw new AlreadyAttempted();
    }

    private void checkOwnership(MessageWorkClaim claim, WorkGuard guard) {
        if (!guard.stillOwned()) {
            throw new StaleWorkClaimException();
        }
        if (store.isCancellationRequested(claim)) {
            throw new JobCancellationRequestedException();
        }
    }

    private ControlContext context(MessageWorkClaim claim, ConversationHistory history) {
        Optional<ContextCompaction> compaction = store.loadCompaction(claim.sessionId());
        return new ControlContext(new ContextHistoryView(history).between(
                compaction.map(value -> value.coveredThrough().value()).orElse(0L), Long.MAX_VALUE),
                compaction.map(ContextCompaction::summary), compaction.map(ContextCompaction::generation).orElse(0L));
    }

    private boolean fits(ConversationModel model, String directive, List<SessionMessage> history,
                         Optional<ContextSummary> summary, long generation) {
        if ("unspecified".equals(model.descriptor().modelId())) {
            return true;
        }
        ContextUsageProjection projection = new ContextUsageProjection(model.descriptor(), directive, List.of(), history,
                generation, summary, "control-no-tools");
        return estimator.estimate(projection, Optional.empty()).tokens() < model.descriptor().contextWindowTokens();
    }

    private record ControlContext(List<SessionMessage> history, Optional<ContextSummary> summary, long generation) {}
    private static final class AlreadyAttempted extends RuntimeException {}
}
