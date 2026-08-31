package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ObservationId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.application.ToolFailureOutput;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class MessageJobService implements MessageJobPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageJobService.class);
    private static final String MODEL_CALL_LIMIT_REACHED = "MODEL_CALL_LIMIT_REACHED";
    private static final String MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID";
    private static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    private static final String CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE";

    private final ConversationStore conversationStore;
    private final ConversationModel conversationModel;
    private final DirectToolRegistry toolRegistry;
    private final Clock clock;
    private final int maxModelCalls;
    private final MessageJobRetryPolicy retryPolicy;
    private final ConversationTelemetry telemetry;

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry toolRegistry,
            Clock clock) {
        this(conversationStore, conversationModel, toolRegistry, clock, Integer.MAX_VALUE,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry toolRegistry,
            Clock clock,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this(conversationStore, conversationModel, toolRegistry, clock, Integer.MAX_VALUE, retryPolicy, telemetry);
    }

    public MessageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry toolRegistry,
            Clock clock,
            int maxModelCalls,
            MessageJobRetryPolicy retryPolicy,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModel = Objects.requireNonNull(conversationModel, "Conversation model must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry must not be null");
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
        while (guard.stillOwned()) {
            List<SessionMessage> history = conversationStore.loadHistory(claim.sessionId());
            ToolSnapshot tools = toolRegistry.snapshot();
            ReservationState reservation = new ReservationState();
            ModelRequest request = new ModelRequest(history, tools);
            ModelReply reply;
            try {
                logModelCallStarted(claim, history.size(), tools.definitions().size());
                reply = conversationModel.respond(request, () -> reserve(claim, guard, reservation),
                        usage -> logModelCallUsage(claim, reservation.ordinal(), usage));
            } catch (BudgetExhausted exception) {
                appendRuntime(claim, guard, List.of(runtime(MODEL_CALL_LIMIT_REACHED)), ConversationStore.JobUpdate.COMPLETE);
                return;
            } catch (ModelCallFailure failure) {
                if (handleModelFailure(claim, guard, reservation, failure)) {
                    continue;
                }
                return;
            }
            if (!guard.stillOwned()) {
                return;
            }
            int ordinal = reservation.ordinal();
            logModelCallDecision(claim, ordinal, reply);
            if (reply instanceof ModelReply.Text text) {
                appendRuntime(claim, guard, List.of(new ConversationStore.AssistantData(text.message())),
                        ConversationStore.JobUpdate.COMPLETE);
                return;
            }
            ModelReply.UseTools useTools = (ModelReply.UseTools) reply;
            if (ordinal == maxModelCalls) {
                List<ConversationStore.MessageData> messages = new ArrayList<>();
                useTools.message().ifPresent(text -> messages.add(new ConversationStore.AssistantData(text)));
                messages.add(runtime(MODEL_CALL_LIMIT_REACHED));
                appendRuntime(claim, guard, messages, ConversationStore.JobUpdate.COMPLETE);
                return;
            }
            List<ConversationStore.MessageData> messages = toolBatch(useTools, tools);
            if (!guard.stillOwned()) {
                return;
            }
            if (!appendRuntime(claim, guard, messages, ConversationStore.JobUpdate.KEEP_WORKING)) {
                return;
            }
        }
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

    private List<ConversationStore.MessageData> toolBatch(ModelReply.UseTools reply, ToolSnapshot tools) {
        List<ConversationStore.MessageData> messages = new ArrayList<>();
        reply.message().ifPresent(text -> messages.add(new ConversationStore.AssistantData(text)));
        for (ToolRequest request : reply.requests()) {
            messages.add(executeTool(tools, request));
        }
        return List.copyOf(messages);
    }

    private ConversationStore.ToolObservationData executeTool(ToolSnapshot tools, ToolRequest request) {
        String output;
        try {
            output = toolRegistry.invoke(tools, request.toolName(), request.input());
            telemetry.tool(request.toolName().value(), "SUCCESS", Optional.empty(), Optional.empty());
        } catch (IllegalArgumentException failure) {
            output = ToolFailureOutput.format(new ToolExecutionFailure("TOOL_INPUT_INVALID", "The tool input is invalid."));
            telemetry.tool(request.toolName().value(), "INVALID_INPUT", Optional.empty(), Optional.empty());
        } catch (ToolExecutionFailure failure) {
            output = ToolFailureOutput.format(failure);
            telemetry.tool(request.toolName().value(), failure.code(), Optional.empty(), Optional.empty());
        }
        return new ConversationStore.ToolObservationData(new ObservationId(UUID.randomUUID().toString()),
                request.toolName().value(), request.input(), output);
    }

    private boolean handleModelFailure(
            MessageWorkClaim claim,
            WorkGuard guard,
            ReservationState reservation,
            ModelCallFailure failure) {
        int ordinal = reservation.ordinal();
        logModelCallFailed(claim, ordinal, failure.kind());
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
        if (!guard.stillOwned()) {
            return false;
        }
        try {
            conversationStore.append(claim, new ConversationStore.MessageBatch(messages, update), clock.instant());
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
            default -> throw new IllegalArgumentException("Unsupported runtime code");
        };
    }

    private static void logModelCallStarted(MessageWorkClaim claim, int historySize, int toolCount) {
        LOGGER.info("model_call_started sessionId={} messageJobId={} historyCount={} visibleToolCount={}",
                claim.sessionId().value(), claim.messageJobId().value(), historySize, toolCount);
    }

    private static void logModelCallUsage(MessageWorkClaim claim, int ordinal,
                                          com.java.system.sessionagent.conversation.domain.ModelUsage usage) {
        LOGGER.info("model_call_usage sessionId={} messageJobId={} ordinal={} usageAvailable={} promptTokens={} completionTokens={} totalTokens={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, usage.available(), usage.promptTokens(),
                usage.completionTokens(), usage.totalTokens());
    }

    private static void logModelCallDecision(MessageWorkClaim claim, int ordinal, ModelReply reply) {
        String category = reply instanceof ModelReply.Text ? "ASSISTANT_TEXT" : "USE_TOOLS";
        LOGGER.info("model_call_decision sessionId={} messageJobId={} ordinal={} decisionCategory={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, category);
    }

    private static void logModelCallFailed(MessageWorkClaim claim, int ordinal, ModelCallFailure.Kind kind) {
        LOGGER.info("model_call_failed sessionId={} messageJobId={} ordinal={} closedFailureKind={}",
                claim.sessionId().value(), claim.messageJobId().value(), ordinal, kind);
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
