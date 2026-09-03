package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
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
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private static final String INVALID_CONVERSATION_HISTORY = "INVALID_CONVERSATION_HISTORY";

    private final ConversationStore conversationStore;
    private final ConversationModel conversationModel;
    private final ToolCatalog toolCatalog;
    private final Clock clock;
    private final int maxModelCalls;
    private final MessageJobRetryPolicy retryPolicy;
    private final ConversationTelemetry telemetry;

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
    public void process(MessageWorkClaim claim, WorkGuard workGuard) {
        Assert.notNull(claim, "Message work claim must not be null");
        Assert.notNull(workGuard, "Work guard must not be null");
        try {
            processClaim(claim, workGuard);
        } catch (ConversationStoreFailure failure) {
            recoverStorageFailure(claim, workGuard, failure);
        }
    }

    private void processClaim(MessageWorkClaim claim, WorkGuard guard) {
        ModelRouteId routeId = conversationModel.routeId();
        try {
            conversationStore.bindModelRoute(claim, routeId);
        } catch (ModelRouteMismatchException exception) {
            appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE);
            return;
        }
        while (guard.stillOwned()) {
            List<SessionMessage> history = conversationStore.loadHistory(claim.sessionId());
            Map<SessionSequence, ModelContinuation> continuations = Optional.ofNullable(conversationStore.loadContinuations(claim))
                    .orElseGet(Map::of);
            if (hasContinuationForAnotherRoute(continuations, routeId)) {
                appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE);
                return;
            }
            try (ToolSnapshot tools = toolCatalog.snapshot()) {
                ReservationState reservation = new ReservationState();
                ModelRequest request = new ModelRequest(history, continuations, tools);
                ModelReply reply;
                Optional<ModelContinuation> continuation;
                long requestStartedAt = System.nanoTime();
                try {
                    ModelCallResult result = conversationModel.respond(request, () -> reserveAndLogModelRequest(claim, guard, reservation,
                                    history.size(), tools.definitions().size()),
                            usage -> { });
                    reply = result.reply();
                    continuation = result.continuation();
                } catch (BudgetExhausted exception) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)), ConversationStore.JobUpdate.COMPLETE);
                    return;
                } catch (ModelCallFailure failure) {
                    if (failure.kind() == ModelCallFailure.Kind.INVALID_HISTORY) {
                        appendRuntime(claim, guard, List.of(runtime(INVALID_CONVERSATION_HISTORY)), ConversationStore.JobUpdate.COMPLETE);
                        return;
                    }
                    if (handleModelFailure(claim, guard, reservation, failure, elapsedSince(requestStartedAt))) {
                        continue;
                    }
                    return;
                }
                if (continuation.isPresent() && !routeId.equals(continuation.orElseThrow().modelRouteId())) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE);
                    return;
                }
                if (!guard.stillOwned()) {
                    return;
                }
                int ordinal = reservation.ordinal();
                logModelResponse(claim, ordinal, reply, elapsedSince(requestStartedAt));
                if (reply instanceof ModelReply.Text text) {
                    appendRuntime(claim, guard, List.of(new ConversationStore.AssistantData(text.message())),
                            ConversationStore.JobUpdate.COMPLETE);
                    return;
                }
                ModelReply.UseTools useTools = (ModelReply.UseTools) reply;
                if (ordinal == maxModelCalls) {
                    appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)), ConversationStore.JobUpdate.COMPLETE);
                    return;
                }
                List<ConversationStore.MessageData> messages = toolBatch(claim, guard, ordinal, useTools, tools);
                if (!guard.stillOwned()) {
                    return;
                }
                if (!appendRuntime(claim, guard, messages, ConversationStore.JobUpdate.KEEP_WORKING, continuation)) {
                    return;
                }
            }
        }
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
            int historySize,
            int toolCount) {
        int ordinal = reserve(claim, guard, state);
        logModelRequest(claim, ordinal, historySize, toolCount);
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
        logToolExecution(claim, ordinal, outcome, duration);
        return new ConversationStore.ToolObservationData(request.toolCallId(), request.toolName().value(), output.asStructuredValue());
    }

    private boolean handleModelFailure(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ModelCallFailure failure,
            Duration duration) {
        int ordinal = reservation.ordinal();
        logModelFailure(claim, ordinal, failure.kind(), duration);
        if (failure.kind() == ModelCallFailure.Kind.CORRECTABLE) {
            ConversationStore.JobUpdate update = ordinal < maxModelCalls
                    ? ConversationStore.JobUpdate.KEEP_WORKING : ConversationStore.JobUpdate.COMPLETE;
            return appendRuntime(claim, guard, List.of(runtime(MODEL_OUTPUT_INVALID)), update)
                    && update == ConversationStore.JobUpdate.KEEP_WORKING;
        }
        if (failure.kind() == ModelCallFailure.Kind.CONTEXT_TOO_LARGE) {
            appendRuntime(claim, guard, List.of(runtime(CONTEXT_TOO_LARGE)), ConversationStore.JobUpdate.COMPLETE);
            return false;
        }
        if (failure.kind() == ModelCallFailure.Kind.TRANSIENT && ordinal < maxModelCalls && scheduleRetry(claim, guard)) {
            return false;
        }
        appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE);
        return false;
    }

    private boolean scheduleRetry(MessageWorkClaim claim, WorkGuard guard) {
        if (!guard.stillOwned()) {
            return false;
        }
        Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
        if (job.isEmpty() || job.orElseThrow().retryCount() >= retryPolicy.transientRetries()) {
            return false;
        }
        Duration delay = retryDelay(job.orElseThrow().retryCount());
        boolean scheduled = conversationStore.scheduleRetry(claim, delay);
        if (scheduled) {
            telemetry.retry("MODEL", delay);
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
            ConversationStore.JobUpdate update) {
        return appendRuntime(claim, guard, messages, update, Optional.empty());
    }

    private boolean appendRuntime(
            MessageWorkClaim claim,
            WorkGuard guard,
            List<ConversationStore.MessageData> messages,
            ConversationStore.JobUpdate update,
            Optional<ModelContinuation> continuation) {
        if (!guard.stillOwned()) {
            return false;
        }
        try {
            conversationStore.append(claim, new ConversationStore.MessageBatch(messages, update, continuation), clock.instant());
            for (ConversationStore.MessageData message : messages) {
                if (message instanceof ConversationStore.RuntimeData runtime) {
                    telemetry.feedback(runtime.code());
                }
            }
            return guard.stillOwned();
        } catch (StaleWorkClaimException exception) {
            return false;
        }
    }

    private void recoverStorageFailure(MessageWorkClaim claim, WorkGuard guard, ConversationStoreFailure failure) {
        if (!guard.stillOwned()) {
            return;
        }
        if (failure.kind() == ConversationStoreFailure.Kind.INVALID_HISTORY) {
            appendRuntime(claim, guard, List.of(runtime(INVALID_CONVERSATION_HISTORY)), ConversationStore.JobUpdate.COMPLETE);
            return;
        }
        try {
            Optional<ConversationStore.MessageJobProjection> job = conversationStore.readJob(claim.messageJobId());
            if (failure.kind() != ConversationStoreFailure.Kind.CONTRACT
                    && job.isPresent()
                    && job.orElseThrow().modelCallCount() < maxModelCalls
                    && job.orElseThrow().retryCount() < retryPolicy.transientRetries()) {
                Duration delay = retryDelay(job.orElseThrow().retryCount());
                conversationStore.scheduleRetry(claim, delay);
                telemetry.retry("STORAGE", delay);
                return;
            }
            appendRuntime(claim, guard, List.of(runtime(MODEL_UNAVAILABLE)), ConversationStore.JobUpdate.COMPLETE);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private static ConversationStore.RuntimeData runtime(String code) {
        return switch (code) {
            case MODEL_CALL_LIMIT_REACHED -> new ConversationStore.RuntimeData(code, "Runtime model call limit reached.");
            case MODEL_OUTPUT_INVALID -> new ConversationStore.RuntimeData(code, "Runtime model output is invalid.");
            case MODEL_UNAVAILABLE -> new ConversationStore.RuntimeData(code, "Runtime model is unavailable.");
            case CONTEXT_TOO_LARGE -> new ConversationStore.RuntimeData(code, "Runtime model context is too large.");
            case INVALID_CONVERSATION_HISTORY -> new ConversationStore.RuntimeData(code, "Runtime conversation history is invalid.");
            default -> throw new IllegalArgumentException("Unsupported runtime code");
        };
    }

    private static void logModelRequest(MessageWorkClaim claim, int ordinal, int historySize, int toolCount) {
        LOGGER.info("model_request sessionId={} messageJobId={} ordinal={} historyCount={} visibleToolCount={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, historySize, toolCount);
    }

    private static void logModelResponse(MessageWorkClaim claim, int ordinal, ModelReply reply, Duration duration) {
        String category = reply instanceof ModelReply.Text ? "ASSISTANT_TEXT" : "USE_TOOLS";
        LOGGER.info("model_response sessionId={} messageJobId={} ordinal={} category={} durationMs={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, category, duration.toMillis());
    }

    private static void logModelFailure(MessageWorkClaim claim, int ordinal, ModelCallFailure.Kind kind, Duration duration) {
        LOGGER.info("model_failure sessionId={} messageJobId={} ordinal={} category={} durationMs={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, failureCategory(kind), duration.toMillis());
    }

    private static void logToolExecution(
            MessageWorkClaim claim,
            int ordinal,
            String outcome,
            Duration duration) {
        LOGGER.info("tool_execution sessionId={} messageJobId={} ordinal={} category={} durationMs={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, outcome, duration.toMillis());
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
