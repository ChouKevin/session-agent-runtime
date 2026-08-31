package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ObservationId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public interface ConversationStore {

    MessageReceipt receive(IncomingMessage incomingMessage);

    Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration);

    boolean extendClaim(MessageWorkClaim claim, Duration leaseDuration);

    List<SessionMessage> loadHistory(SessionId sessionId);

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

    sealed interface MessageData permits AssistantData, ToolObservationData, RuntimeData {
    }

    record AssistantData(String message) implements MessageData {

        public AssistantData {
            Assert.hasText(message, "Assistant message must not be blank");
        }
    }

    record ToolObservationData(
            ObservationId observationId,
            String toolName,
            String input,
            String output) implements MessageData {

        public ToolObservationData {
            Assert.notNull(observationId, "Observation ID must not be null");
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.notNull(input, "Tool input must not be null");
            Assert.notNull(output, "Tool output must not be null");
        }
    }

    record RuntimeData(String code, String message) implements MessageData {

        public RuntimeData {
            Assert.hasText(code, "Runtime message code must not be blank");
            Assert.hasText(message, "Runtime message must not be blank");
        }
    }

    record MessageBatch(List<MessageData> messages, JobUpdate jobUpdate) {

        public MessageBatch {
            Assert.notEmpty(messages, "Message batch must not be empty");
            messages = List.copyOf(messages);
            Assert.notNull(jobUpdate, "Job update must not be null");
        }
    }
}
