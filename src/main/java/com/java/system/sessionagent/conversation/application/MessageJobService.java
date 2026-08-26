package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallContext;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory;
import com.java.system.sessionagent.tool.application.ToolFailureFeedback;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public final class MessageJobService implements MessageJobPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageJobService.class);
    private static final int MAX_MODEL_CALLS = 12;
    private final ConversationStore conversationStore;
    private final ConversationModel conversationModel;
    private final DirectToolRegistry toolRegistry;
    private final ToolResultEnvelopeFactory envelopeFactory;
    private final CitationValidator citationValidator;
    private final Clock clock;
    private final MessageJobRetryPolicy retryPolicy;
    private final ConversationTelemetry telemetry;

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry toolRegistry,
            Clock clock) {
        this(conversationStore, conversationModel, toolRegistry, clock,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());
    }

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry toolRegistry,
            Clock clock,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModel = Objects.requireNonNull(conversationModel, "Conversation model must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry must not be null");
        this.envelopeFactory = new ToolResultEnvelopeFactory();
        this.citationValidator = new CitationValidator(this.conversationStore);
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "Message job retry policy must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    @Override
    public void process(MessageWorkClaim claim, WorkGuard workGuard) {
        Assert.notNull(claim, "Message work claim must not be null");
        Assert.notNull(workGuard, "Work guard must not be null");
        try {
            processClaim(claim, workGuard);
        } catch (ConversationStoreFailure failure) {
            recoverStorageFailure(claim, workGuard, failure);
        }
    }

    private void processClaim(MessageWorkClaim claim, WorkGuard workGuard) {
        boolean finalReplyRequested = false;
        while (workGuard.stillOwned()) {
            List<SessionMessage> history = conversationStore.loadHistory(claim.sessionId(), claim.messageJobId());
            OptionalIntReservation reservation = reserve(claim, workGuard);
            if (!reservation.reserved()) {
                appendFeedback(claim, workGuard, FeedbackCode.CALL_LIMIT_REACHED, true, ToolFeedbackDetails.empty());
                return;
            }
            ModelCallContext callContext = new ModelCallContext(claim.sessionId(), claim.messageJobId(), reservation.ordinal());
            boolean finalCall = reservation.ordinal() == MAX_MODEL_CALLS;
            if (finalReplyRequested || finalCall) {
                if (!processFinalReply(claim, workGuard, history, callContext, finalCall)) {
                    return;
                }
                continue;
            }
            ToolSnapshot snapshot = toolRegistry.snapshot();
            logModelCallStarted(claim, reservation.ordinal(), "PLAN", history.size(), snapshot.definitions().size());
            ModelDecision decision;
            try {
                decision = conversationModel.plan(new ModelRequest(history, snapshot, callContext),
                        usage -> logModelCallUsage(claim, reservation.ordinal(), usage));
            } catch (ModelCallFailure failure) {
                logModelCallFailed(claim, reservation.ordinal(), failure.kind());
                if (!handleFailure(claim, workGuard, ConversationFailurePolicy.model(failure), ToolFeedbackDetails.empty(), "MODEL")) { return; }
                continue;
            }
            logModelCallDecision(claim, reservation.ordinal(), decision);
            if (!workGuard.stillOwned()) { return; }
            if (decision instanceof ModelDecision.UseTool useTool) {
                if (!executeTool(claim, workGuard, snapshot, useTool)) { return; }
                continue;
            }
            finalReplyRequested = true;
        }
    }

    private boolean processFinalReply(MessageWorkClaim claim, WorkGuard workGuard, List<SessionMessage> history,
                                      ModelCallContext callContext, boolean finalCall) {
        logModelCallStarted(claim, callContext.ordinal(), "FINAL_REPLY", history.size(), 0);
        AssistantReply reply;
        try {
            reply = conversationModel.reply(new ReplyRequest(history, callContext),
                    usage -> logModelCallUsage(claim, callContext.ordinal(), usage));
        } catch (ModelCallFailure failure) {
            logModelCallFailed(claim, callContext.ordinal(), failure.kind());
            if (finalCall && failure.kind() == ModelCallFailure.Kind.TRANSIENT) {
                appendFeedback(claim, workGuard, FeedbackCode.DEPENDENCY_UNAVAILABLE, true, ToolFeedbackDetails.empty());
                return false;
            }
            if (finalCall && failure.kind() == ModelCallFailure.Kind.CORRECTABLE) {
                appendFeedback(claim, workGuard, FeedbackCode.CALL_LIMIT_REACHED, true, ToolFeedbackDetails.empty());
                return false;
            }
            return handleFailure(claim, workGuard, ConversationFailurePolicy.model(failure), ToolFeedbackDetails.empty(), "MODEL");
        }
        logModelCallDecision(claim, callContext.ordinal(), "ASSISTANT_REPLY");
        if (!workGuard.stillOwned()) {
            return false;
        }
        return validateReply(claim, workGuard, reply, finalCall);
    }

    private void recoverStorageFailure(MessageWorkClaim claim, WorkGuard guard, ConversationStoreFailure failure) {
        if (!guard.stillOwned()) {
            return;
        }
        if (failure.kind() == ConversationStoreFailure.Kind.CONTRACT) {
            appendStorageFeedback(claim, guard, FeedbackCode.DATABASE_CONTRACT_ERROR);
            return;
        }
        try {
            Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
            if (job.isPresent() && job.get().modelCallCount() >= MAX_MODEL_CALLS) {
                appendStorageFeedback(claim, guard, FeedbackCode.DEPENDENCY_UNAVAILABLE);
                return;
            }
            if (job.isPresent() && job.get().retryCount() < retryPolicy.transientRetries()) {
                Duration delay = retryDelay(job.get().retryCount(), Optional.empty());
                conversationStore.scheduleRetry(claim, delay);
                telemetry.retry("STORAGE", delay);
                return;
            }
            appendStorageFeedback(claim, guard, FeedbackCode.DEPENDENCY_UNAVAILABLE);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void appendStorageFeedback(MessageWorkClaim claim, WorkGuard guard, FeedbackCode code) {
        if (!guard.stillOwned()) {
            return;
        }
        try {
            conversationStore.appendFeedback(claim, code.name(), safeMessage(code), true,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), clock.instant());
            telemetry.feedback(code.name());
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private OptionalIntReservation reserve(MessageWorkClaim claim, WorkGuard guard) {
        if (!guard.stillOwned()) { return OptionalIntReservation.unavailable(); }
        java.util.OptionalInt value = conversationStore.reserveModelCall(claim, clock.instant());
        return value.isPresent() ? new OptionalIntReservation(true, value.getAsInt()) : OptionalIntReservation.unavailable();
    }

    private boolean executeTool(MessageWorkClaim claim, WorkGuard guard, ToolSnapshot snapshot, ModelDecision.UseTool toolCall) {
        ToolExecution execution;
        try {
            execution = toolRegistry.execute(snapshot, toolCall.toolName(), toolCall.arguments());
        } catch (IllegalArgumentException failure) {
            telemetry.tool(toolCall.toolName().value(), "INVALID_INPUT", Optional.empty(), Optional.empty());
            return appendFeedback(claim, guard, FeedbackCode.INVALID_TOOL_INPUT, false, toolDetails(toolCall));
        } catch (ToolExecutionFailure failure) {
            telemetry.tool(toolCall.toolName().value(), failure.kind().name(), Optional.empty(), Optional.empty());
            if (failure.kind() == ToolExecutionFailure.Kind.REVISION_OUTDATED) {
                return appendRevisionOutdatedFeedback(claim, guard, toolDetails(toolCall), failure.revisionOutdated().orElseThrow());
            }
            return handleFailure(claim, guard, ConversationFailurePolicy.tool(failure), toolDetails(toolCall), "TOOL");
        }
        if (!guard.stillOwned()) { return false; }
        ToolResultEnvelopeFactory.ValidatedResult result;
        try {
            result = envelopeFactory.validate(execution);
        } catch (ToolExecutionFailure failure) {
            telemetry.tool(toolCall.toolName().value(), failure.kind().name(), Optional.empty(), Optional.empty());
            return handleFailure(claim, guard, ConversationFailurePolicy.tool(failure), toolDetails(toolCall), "TOOL");
        } catch (RuntimeException failure) {
            ToolExecutionFailure invalidResponse = ToolExecutionFailure.invalidResponse();
            telemetry.tool(toolCall.toolName().value(), invalidResponse.kind().name(), Optional.empty(), Optional.empty());
            return handleFailure(claim, guard, ConversationFailurePolicy.tool(invalidResponse), toolDetails(toolCall), "TOOL");
        }
        ResultId resultId = new ResultId(UUID.randomUUID().toString());
        String resultJson;
        try {
            resultJson = envelopeFactory.envelope(resultId.value(), result);
        } catch (ToolExecutionFailure failure) {
            telemetry.tool(toolCall.toolName().value(), failure.kind().name(), Optional.empty(), Optional.empty());
            return handleFailure(claim, guard, ConversationFailurePolicy.tool(failure), toolDetails(toolCall), "TOOL");
        } catch (RuntimeException failure) {
            ToolExecutionFailure invalidResponse = ToolExecutionFailure.invalidResponse();
            telemetry.tool(toolCall.toolName().value(), invalidResponse.kind().name(), Optional.empty(), Optional.empty());
            return handleFailure(claim, guard, ConversationFailurePolicy.tool(invalidResponse), toolDetails(toolCall), "TOOL");
        }
        ConversationStore.ToolData data = new ConversationStore.ToolData(execution.name().value(), execution.version(), execution.kind(),
                execution.canonicalArguments(), execution.repositoryId(), execution.revision(), resultJson, execution.citeable());
        try {
            conversationStore.appendTool(claim, resultId, toolCall.callId(), toolCall.modelContext(), data, clock.instant());
            telemetry.tool(execution.name().value(), "SUCCESS", execution.repositoryId(), execution.revision());
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            telemetry.tool(toolCall.toolName().value(), "STALE", Optional.empty(), Optional.empty());
            return false;
        }
    }

    private boolean validateReply(MessageWorkClaim claim, WorkGuard guard, AssistantReply reply, boolean finalCall) {
        CitationValidator.Validation validation = citationValidator.validate(claim.sessionId(), reply.citations());
        if (validation instanceof CitationValidator.Validation.Accepted accepted) {
            if (guard.stillOwned()) {
                try {
                    conversationStore.appendAssistant(claim, new AssistantReply(reply.message(), accepted.citations()), clock.instant());
                } catch (StaleWorkClaimException exception) {
                    return false;
                }
            }
            return false;
        }
        if (validation instanceof CitationValidator.Validation.Correctable correctable) {
            logCitationRejected(claim, correctable.reason(), reply.citations().size());
            if (finalCall) {
                appendFeedback(claim, guard, FeedbackCode.CALL_LIMIT_REACHED, true, ToolFeedbackDetails.empty());
                return false;
            }
            return appendFeedback(claim, guard, FeedbackCode.INVALID_CITATION, false, ToolFeedbackDetails.empty());
        }
        appendFeedback(claim, guard, FeedbackCode.INVALID_CITATION, true, ToolFeedbackDetails.empty());
        return false;
    }

    private boolean handleFailure(MessageWorkClaim claim, WorkGuard guard, ConversationFailurePolicy.Failure failure,
                                  ToolFeedbackDetails toolDetails, String retryCategory) {
        if (failure.action() == ConversationFailurePolicy.Action.CORRECTABLE) {
            return appendFeedback(claim, guard, failure.code(), false, toolDetails);
        }
        if (failure.action() == ConversationFailurePolicy.Action.RETRY) {
            scheduleRetry(claim, guard, failure.retryAfter(), toolDetails, retryCategory);
            return false;
        }
        appendFeedback(claim, guard, failure.code(), true, toolDetails);
        return false;
    }

    private void scheduleRetry(MessageWorkClaim claim, WorkGuard guard, Optional<Duration> retryAfter,
                               ToolFeedbackDetails toolDetails, String retryCategory) {
        if (!guard.stillOwned()) { return; }
        Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
        if (job.isEmpty() || job.get().retryCount() >= retryPolicy.transientRetries()) {
            appendFeedback(claim, guard, FeedbackCode.DEPENDENCY_UNAVAILABLE, true, toolDetails);
            return;
        }
        Duration delay = retryDelay(job.get().retryCount(), retryAfter);
        conversationStore.scheduleRetry(claim, delay);
        telemetry.retry(retryCategory, delay);
    }

    private Duration retryDelay(int retryCount, Optional<Duration> retryAfter) {
        Duration requested = retryAfter.orElseGet(() -> exponentialRetryDelay(retryCount));
        if (requested.isNegative()) {
            return Duration.ZERO;
        }
        return requested.compareTo(retryPolicy.maximumBackoff()) > 0 ? retryPolicy.maximumBackoff() : requested;
    }

    private Duration exponentialRetryDelay(int retryCount) {
        if (retryCount < 0 || retryCount >= Long.SIZE - 1) {
            return retryPolicy.maximumBackoff();
        }
        return Duration.ofSeconds(1L << retryCount);
    }

    private boolean appendFeedback(MessageWorkClaim claim, WorkGuard guard, FeedbackCode code, boolean terminal,
                                   ToolFeedbackDetails toolDetails) {
        if (!guard.stillOwned()) {
            return false;
        }
        try {
            conversationStore.appendFeedback(claim, code.name(), safeMessage(code), terminal,
                    toolDetails.modelCallId(), toolDetails.toolName(), toolDetails.arguments(), toolDetails.modelContext(), clock.instant());
            telemetry.feedback(code.name());
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            return false;
        }
    }

    private boolean appendRevisionOutdatedFeedback(MessageWorkClaim claim, WorkGuard guard, ToolFeedbackDetails toolDetails,
                                                   ToolExecutionFailure.RevisionOutdatedDetails details) {
        if (!guard.stillOwned()) {
            return false;
        }
        String payload = ToolFailureFeedback.revisionOutdated(details);
        try {
            conversationStore.appendFeedback(claim, FeedbackCode.REVISION_OUTDATED.name(), payload, false,
                    toolDetails.modelCallId(), toolDetails.toolName(), toolDetails.arguments(), toolDetails.modelContext(), clock.instant());
            telemetry.feedback(FeedbackCode.REVISION_OUTDATED.name());
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            return false;
        }
    }

    private static String safeMessage(FeedbackCode code) { return "Runtime feedback: " + code.name(); }

    private static void logModelCallStarted(MessageWorkClaim claim, int ordinal, String phase, int historyCount,
                                            int visibleToolCount) {
        LOGGER.info("model_call_started sessionId={} messageJobId={} ordinal={} phase={} historyCount={} visibleToolCount={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, phase, historyCount, visibleToolCount);
    }

    private static void logModelCallUsage(MessageWorkClaim claim, int ordinal,
                                          com.java.system.sessionagent.conversation.domain.ModelUsage usage) {
        LOGGER.info("model_call_usage sessionId={} messageJobId={} ordinal={} usageAvailable={} promptTokens={} completionTokens={} totalTokens={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, usage.available(), usage.promptTokens(),
                usage.completionTokens(), usage.totalTokens());
    }

    private static void logModelCallDecision(MessageWorkClaim claim, int ordinal, ModelDecision decision) {
        String category = decision instanceof ModelDecision.UseTool ? "USE_TOOL" : "ANSWER_READY";
        logModelCallDecision(claim, ordinal, category);
    }

    private static void logModelCallDecision(MessageWorkClaim claim, int ordinal, String category) {
        LOGGER.info("model_call_decision sessionId={} messageJobId={} ordinal={} decisionCategory={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, category);
    }

    private static void logModelCallFailed(MessageWorkClaim claim, int ordinal, ModelCallFailure.Kind kind) {
        LOGGER.info("model_call_failed sessionId={} messageJobId={} ordinal={} closedFailureKind={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, kind);
    }

    private static void logCitationRejected(MessageWorkClaim claim, CitationValidator.CorrectionReason reason,
                                            int citationCount) {
        LOGGER.info("assistant_citation_rejected sessionId={} messageJobId={} phase=FINAL_REPLY reason={} citationCount={}",
                claim.sessionId().value(), claim.messageJobId().value(), reason, citationCount);
    }

    private static ToolFeedbackDetails toolDetails(ModelDecision.UseTool toolCall) {
        return new ToolFeedbackDetails(Optional.of(toolCall.callId()), Optional.of(toolCall.toolName().value()),
                Optional.of(toolCall.arguments()), Optional.of(toolCall.modelContext()));
    }

    private record ToolFeedbackDetails(
            Optional<String> modelCallId,
            Optional<String> toolName,
            Optional<String> arguments,
            Optional<String> modelContext) {
        private ToolFeedbackDetails {
            Objects.requireNonNull(modelCallId, "Model call ID must not be null");
            Objects.requireNonNull(toolName, "Tool name must not be null");
            Objects.requireNonNull(arguments, "Tool arguments must not be null");
            Objects.requireNonNull(modelContext, "Model context must not be null");
        }

        private static ToolFeedbackDetails empty() {
            return new ToolFeedbackDetails(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private record OptionalIntReservation(boolean reserved, int ordinal) {
        private static OptionalIntReservation unavailable() { return new OptionalIntReservation(false, 0); }
    }

}
