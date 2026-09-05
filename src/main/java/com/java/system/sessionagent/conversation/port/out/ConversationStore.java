package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public interface ConversationStore {

    MessageReceipt receive(IncomingMessage incomingMessage);

    Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration);

    boolean extendClaim(MessageWorkClaim claim, Duration leaseDuration);

    List<SessionMessage> loadHistory(SessionId sessionId);

    void bindModelRoute(MessageWorkClaim claim, ModelRouteId modelRouteId);

    Map<SessionSequence, ModelContinuation> loadContinuations(MessageWorkClaim claim);

    default Optional<ContextUsageCheckpoint> loadUsageCheckpoint(
            SessionId sessionId, ModelDescriptor model, String requestShapeFingerprint, long compactGeneration) {
        return Optional.empty();
    }

    OptionalInt reserveModelCall(MessageWorkClaim claim, int maxModelCalls, Instant now);

    void append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt);

    boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay);

    Optional<MessageJobProjection> readJob(MessageJobId messageJobId);

    record MessageJobProjection(
            MessageJobId messageJobId,
            SessionId sessionId,
            JobStatus status,
            int retryCount,
            int modelCallCount) {
    }

    enum JobUpdate {
        KEEP_WORKING,
        COMPLETE
    }

    sealed interface MessageData permits AssistantData, AssistantToolCallsData, ToolObservationData, RuntimeData {
    }

    record AssistantData(String message) implements MessageData {

        public AssistantData {
            Assert.hasText(message, "Assistant message must not be blank");
        }
    }

    record AssistantToolCallsData(Optional<String> message, List<ToolCallData> calls) implements MessageData {

        public AssistantToolCallsData {
            Assert.notNull(message, "Assistant tool call message must not be null");
            message.ifPresent(value -> Assert.hasText(value, "Assistant tool call message must not be blank"));
            Assert.notEmpty(calls, "Assistant tool calls must not be empty");
            calls = List.copyOf(calls);
        }
    }

    record ToolCallData(ToolCallId toolCallId, String toolName, java.util.Map<String, Object> arguments) {

        public ToolCallData {
            Assert.notNull(toolCallId, "Tool call ID must not be null");
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.notNull(arguments, "Tool arguments must not be null");
            arguments = ToolRequest.freezeArguments(arguments);
        }
    }

    record ToolObservationData(
            ToolCallId toolCallId,
            String toolName,
            Object output) implements MessageData {

        public ToolObservationData {
            Assert.notNull(toolCallId, "Tool call ID must not be null");
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.notNull(output, "Tool output must not be null");
        }
    }

    record RuntimeData(String code, String message) implements MessageData {

        public RuntimeData {
            Assert.hasText(code, "Runtime message code must not be blank");
            Assert.hasText(message, "Runtime message must not be blank");
        }
    }

    record UsageCheckpointData(ModelDescriptor model, int modelCallOrdinal, long promptTokens, long completionTokens,
                               long totalTokens, String requestShapeFingerprint, long compactGeneration) {
        public UsageCheckpointData {
            Assert.notNull(model, "Usage checkpoint model must not be null");
            Assert.isTrue(modelCallOrdinal > 0, "Usage checkpoint model call ordinal must be positive");
            Assert.isTrue(promptTokens >= 0 && completionTokens >= 0 && totalTokens >= 0,
                    "Usage checkpoint token counts must not be negative");
            Assert.hasText(requestShapeFingerprint, "Usage checkpoint request shape fingerprint must not be blank");
            Assert.isTrue(compactGeneration >= 0, "Usage checkpoint compact generation must not be negative");
        }
    }

    record MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, Optional<ModelContinuation> continuation,
                        Optional<UsageCheckpointData> usageCheckpoint) {

        public MessageBatch {
            Assert.notEmpty(messages, "Message batch must not be empty");
            messages = List.copyOf(messages);
            Assert.notNull(jobUpdate, "Job update must not be null");
            Assert.notNull(continuation, "Model continuation must not be null");
            Assert.notNull(usageCheckpoint, "Usage checkpoint must not be null");
            long toolCallEvents = messages.stream().filter(AssistantToolCallsData.class::isInstance).count();
            Assert.isTrue(continuation.isEmpty() || (jobUpdate == JobUpdate.KEEP_WORKING && toolCallEvents == 1),
                    "Model continuation requires one working assistant tool call event");
        }

        public MessageBatch(List<MessageData> messages, JobUpdate jobUpdate) {
            this(messages, jobUpdate, Optional.empty(), Optional.empty());
        }

        public MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, Optional<ModelContinuation> continuation) {
            this(messages, jobUpdate, continuation, Optional.empty());
        }
    }
}
