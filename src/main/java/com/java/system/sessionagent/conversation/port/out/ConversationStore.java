package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.port.ArtifactReceipt;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ConversationStore {

    MessageReceipt receive(
            IncomingMessage incomingMessage,
            ModelSelection defaultSelection,
            ModelSelectionLookup selectionLookup);

    Optional<ModelSelection> selectModel(SessionId sessionId, ModelSelection selection);

    Optional<MessageWorkClaim> claimNext(
            String workerId,
            Duration leaseDuration,
            Set<SessionId> excludedSessionIds);

    default Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration) {
        return claimNext(workerId, leaseDuration, Set.of());
    }

    boolean extendClaim(MessageWorkClaim claim, Duration leaseDuration);

    List<SessionMessage> loadHistory(SessionId sessionId);

    ConversationHistory loadContextHistory(SessionId sessionId);

    UUID startSkillCommand(MessageWorkClaim claim);

    JobStatus resolveSkillCommand(MessageWorkClaim claim, SkillReadResult result, Instant createdAt);

    default Optional<List<SessionMessage>> loadHistoryPage(SessionId sessionId, long afterSequence, int limit) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        Assert.isTrue(afterSequence >= 0 && limit > 0,
                "History pagination must be nonnegative with a positive limit");
        List<SessionMessage> history = loadHistory(requiredSessionId);
        if (history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(history.stream()
                .filter(message -> message.sequence().value() > afterSequence)
                .sorted(Comparator.comparingLong(message -> message.sequence().value()))
                .limit(limit)
                .toList());
    }

    void bindModelRoute(MessageWorkClaim claim, ModelRouteId modelRouteId);

    Map<SessionSequence, ModelContinuation> loadContinuations(MessageWorkClaim claim);

    default Optional<ContextUsageCheckpoint> loadUsageCheckpoint(
            SessionId sessionId, ModelDescriptor model, String requestShapeFingerprint, long compactGeneration) {
        return Optional.empty();
    }

    default Optional<ContextUsageCheckpoint> loadUsageCheckpoint(
            SessionId sessionId,
            ModelSelection selection,
            ModelDescriptor model,
            String requestShapeFingerprint,
            long compactGeneration) {
        return Optional.empty();
    }

    default Optional<ContextCompaction> loadCompaction(SessionId sessionId) {
        return Optional.empty();
    }

    default boolean hasOverflowCompaction(MessageJobId messageJobId) {
        return false;
    }

    default void compact(MessageWorkClaim claim, CompactionData compaction, Instant createdAt) {
        throw new UnsupportedOperationException("Context compaction is not supported");
    }

    JobControl loadJobControl(MessageWorkClaim claim);

    ModelCallReservationResult reserveModelCall(
            MessageWorkClaim claim, ModelCallPurpose purpose, int maxExecutionCalls, Instant now);

    JobControl resolveFollowUp(MessageWorkClaim claim, WaitingChoice expected, FollowUpIntent intent, Instant now);

    JobControl enterWrapUp(MessageWorkClaim claim, Instant now);

    void updateProgress(MessageWorkClaim claim, JobProgress progress);

    JobStatus append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt);

    boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay, JobFailure failure);

    Optional<MessageJobProjection> readJob(MessageJobId messageJobId);

    Optional<MessageJobProjection> requestCancellation(MessageJobId messageJobId);

    int requestSessionCancellation(SessionId sessionId);

    boolean isCancellationRequested(MessageWorkClaim claim);

    default Optional<SessionProjection> readSession(SessionId sessionId) {
        return Optional.empty();
    }

    record MessageJobProjection(
            MessageJobId messageJobId,
            SessionId sessionId,
            JobStatus status,
            int retryCount,
            int modelCallCount,
            Optional<JobProgress> progress,
            Optional<JobFailure> failure,
            Optional<Instant> cancellationRequestedAt) {
    }

    record SessionProjection(SessionId sessionId, Instant createdAt, Optional<MessageJobProjection> currentJob, String selectedModel) {
        public SessionProjection {
            Assert.notNull(sessionId, "Session ID must not be null");
            Assert.notNull(createdAt, "Session creation time must not be null");
            Assert.notNull(currentJob, "Current message job must not be null");
            Assert.hasText(selectedModel, "Selected model must not be blank");
        }

    }

    enum JobUpdate {
        KEEP_WORKING,
        COMPLETE,
        CANCEL
    }
    enum CompletionDisposition {
        NONE, ANSWER, WAITING
    }


    sealed interface MessageData permits AssistantData, AssistantToolCallsData, ToolObservationData, RuntimeData, SkillData {
    }

    record SkillData(SkillResource resource) implements MessageData {
        public SkillData {
            Objects.requireNonNull(resource, "Skill resource must not be null");
        }
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
            Object output,
            Optional<ArtifactReceipt> artifactReceipt,
            Optional<SkillReadReceipt> skillReadReceipt) implements MessageData {

        public ToolObservationData(ToolCallId toolCallId, String toolName, Object output) {
            this(toolCallId, toolName, output, Optional.empty(), Optional.empty());
        }

        public ToolObservationData(ToolCallId toolCallId, String toolName, Object output,
                Optional<ArtifactReceipt> artifactReceipt) {
            this(toolCallId, toolName, output, artifactReceipt, Optional.empty());
        }

        public ToolObservationData {
            Assert.notNull(toolCallId, "Tool call ID must not be null");
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.notNull(output, "Tool output must not be null");
            Assert.notNull(artifactReceipt, "Artifact receipt must not be null");
            Assert.notNull(skillReadReceipt, "Skill read receipt must not be null");
        }
    }

    record RuntimeData(String code, String message) implements MessageData {

        public RuntimeData {
            Assert.hasText(code, "Runtime message code must not be blank");
            Assert.hasText(message, "Runtime message must not be blank");
        }
    }

    record UsageCheckpointData(ModelSelection selection, ModelDescriptor model, int modelCallOrdinal, long promptTokens, long completionTokens,
                               long totalTokens, String requestShapeFingerprint, long compactGeneration) {
        public UsageCheckpointData {
            Assert.notNull(selection, "Usage checkpoint model selection must not be null");
            Assert.notNull(model, "Usage checkpoint model must not be null");
            Assert.isTrue(modelCallOrdinal > 0, "Usage checkpoint model call ordinal must be positive");
            Assert.isTrue(promptTokens >= 0 && completionTokens >= 0 && totalTokens >= 0,
                    "Usage checkpoint token counts must not be negative");
            Assert.hasText(requestShapeFingerprint, "Usage checkpoint request shape fingerprint must not be blank");
            Assert.isTrue(compactGeneration >= 0, "Usage checkpoint compact generation must not be negative");
        }
    }

    record CompactionData(
            long generation,
            ContextCompaction.Reason reason,
            String summary,
            SessionSequence coveredThrough,
            ModelDescriptor model,
            String requestShapeFingerprint,
            long estimateBeforeTokens,
            long estimateAfterTokens) {
        public CompactionData {
            Assert.isTrue(generation > 0, "Context compaction generation must be positive");
            Assert.notNull(reason, "Context compaction reason must not be null");
            Assert.hasText(summary, "Context compaction summary must not be blank");
            Assert.notNull(coveredThrough, "Context compaction boundary must not be null");
            Assert.notNull(model, "Context compaction model must not be null");
            Assert.hasText(requestShapeFingerprint, "Context compaction fingerprint must not be blank");
            Assert.isTrue(estimateBeforeTokens >= 0 && estimateAfterTokens >= 0,
                    "Context compaction estimates must not be negative");
        }
    }

    record MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, Optional<ModelContinuation> continuation,
                        Optional<UsageCheckpointData> usageCheckpoint, Optional<JobFailure> failure,
                        CompletionDisposition completionDisposition) {

        public MessageBatch {
            Assert.notEmpty(messages, "Message batch must not be empty");
            messages = List.copyOf(messages);
            Assert.notNull(jobUpdate, "Job update must not be null");
            Assert.notNull(continuation, "Model continuation must not be null");
            Assert.notNull(usageCheckpoint, "Usage checkpoint must not be null");
            Assert.notNull(failure, "Job failure must not be null");
            Assert.notNull(completionDisposition, "Completion disposition must not be null");
            Assert.isTrue(completionDisposition == CompletionDisposition.NONE || jobUpdate == JobUpdate.COMPLETE,
                    "Answer and waiting dispositions require completion");
            long toolCallEvents = messages.stream().filter(AssistantToolCallsData.class::isInstance).count();
            Assert.isTrue(continuation.isEmpty() || (jobUpdate == JobUpdate.KEEP_WORKING && toolCallEvents == 1),
                    "Model continuation requires one working assistant tool call event");
            Assert.isTrue(jobUpdate != JobUpdate.CANCEL || failure.isEmpty(),
                    "Cancellation is not a job failure");
        }
        public MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, CompletionDisposition completionDisposition) {
            this(messages, jobUpdate, Optional.empty(), Optional.empty(), Optional.empty(), completionDisposition);
        }

        public MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, Optional<ModelContinuation> continuation,
                            CompletionDisposition completionDisposition) {
            this(messages, jobUpdate, continuation, Optional.empty(), Optional.empty(), completionDisposition);
        }

        public MessageBatch(List<MessageData> messages, JobUpdate jobUpdate, Optional<ModelContinuation> continuation,
                            Optional<UsageCheckpointData> usageCheckpoint, CompletionDisposition completionDisposition) {
            this(messages, jobUpdate, continuation, usageCheckpoint, Optional.empty(), completionDisposition);
        }
    }
}
