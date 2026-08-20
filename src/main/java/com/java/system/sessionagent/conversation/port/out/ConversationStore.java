package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.tool.domain.ToolKind;
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

    List<SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId);

    OptionalInt reserveModelCall(MessageWorkClaim claim, Instant now);

    ToolMessage appendTool(
            MessageWorkClaim claim,
            ResultId resultId,
            String modelCallId,
            String modelContext,
            ToolData toolData,
            Instant createdAt);

    FeedbackMessage appendFeedback(
            MessageWorkClaim claim,
            String code,
            String message,
            boolean terminal,
            Optional<String> modelCallId,
            Optional<String> toolName,
            Optional<String> rejectedArguments,
            Optional<String> modelContext,
            Instant createdAt);

    AssistantMessage appendAssistant(MessageWorkClaim claim, AssistantReply reply, Instant createdAt);

    boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay);

    Optional<MessageJobProjection> readJob(MessageJobId messageJobId);

    Optional<ResultProjection> readResult(ResultId resultId);

    record MessageJobProjection(
            MessageJobId messageJobId,
            SessionId sessionId,
            JobStatus status,
            int retryCount,
            int modelCallCount,
            Optional<SessionSequence> replySequence) {
    }

    record ResultProjection(
            ResultId resultId,
            SessionId sessionId,
            String toolName,
            String toolVersion,
            String canonicalArguments,
            Optional<String> repositoryId,
            Optional<String> revision,
            String resultJson,
            boolean citeable) {

        public ResultProjection {
            Assert.notNull(resultId, "Result ID must not be null");
            Assert.notNull(sessionId, "Session ID must not be null");
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.hasText(toolVersion, "Tool version must not be blank");
            Assert.hasText(canonicalArguments, "Canonical arguments must not be blank");
            Assert.notNull(repositoryId, "Repository ID must not be null");
            Assert.notNull(revision, "Revision must not be null");
            repositoryId.ifPresent(value -> Assert.hasText(value, "Repository ID must not be blank"));
            revision.ifPresent(value -> Assert.hasText(value, "Revision must not be blank"));
            Assert.hasText(resultJson, "Result JSON must not be blank");
            if (citeable && (repositoryId.isEmpty() || revision.isEmpty())) {
                throw new IllegalArgumentException("Citeable result requires repository and revision");
            }
            if (!citeable && (repositoryId.isPresent() || revision.isPresent())) {
                throw new IllegalArgumentException("Catalog result must not have repository or revision");
            }
        }
    }

    record ToolData(
            String toolName,
            String toolVersion,
            ToolKind kind,
            String canonicalArguments,
            Optional<String> repositoryId,
            Optional<String> revision,
            String resultJson,
            boolean citeable) {

        public ToolData {
            Assert.hasText(toolName, "Tool name must not be blank");
            Assert.hasText(toolVersion, "Tool version must not be blank");
            Assert.notNull(kind, "Tool kind must not be null");
            Assert.hasText(canonicalArguments, "Canonical arguments must not be blank");
            Assert.notNull(repositoryId, "Repository ID must not be null");
            Assert.notNull(revision, "Revision must not be null");
            repositoryId.ifPresent(value -> Assert.hasText(value, "Repository ID must not be blank"));
            revision.ifPresent(value -> Assert.hasText(value, "Revision must not be blank"));
            Assert.hasText(resultJson, "Result JSON must not be blank");
            if (kind == ToolKind.CATALOG && (citeable || repositoryId.isPresent() || revision.isPresent())) {
                throw new IllegalArgumentException("Catalog tool result must be nonciteable without repository or revision");
            }
            if (kind == ToolKind.SOURCE && (!citeable || repositoryId.isEmpty() || revision.isEmpty())) {
                throw new IllegalArgumentException("Source tool result requires citation repository and revision");
            }
        }

        public String persistedKind() {
            return kind.name();
        }
    }
}
