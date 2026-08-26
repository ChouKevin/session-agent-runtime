package com.java.system.sessionagent.web;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.ConversationResultView;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class MessageResponses {

    private MessageResponses() {
    }

    public record SendMessageResponse(String sessionId, String messageJobId) {
    }

    public record MessageJobResponse(
            String messageJobId,
            String sessionId,
            String status,
            int retryCount,
            int modelCallCount,
            Optional<Long> replySequence) {
        static MessageJobResponse from(MessageJobView view) {
            return new MessageJobResponse(view.messageJobId(), view.sessionId(), view.status().name(), view.retryCount(),
                    view.modelCallCount(), view.replySequence().isPresent() ? Optional.of(view.replySequence().getAsLong()) : Optional.empty());
        }
    }

    public record SessionMessageResponse(
            long sequence,
            Instant createdAt,
            String role,
            Optional<String> messageJobId,
            Optional<String> participantId,
            Optional<String> message,
            Optional<List<String>> citations,
            Optional<String> resultId,
            Optional<String> toolName,
            Optional<String> toolVersion,
            Optional<String> repositoryId,
            Optional<String> revision,
            Optional<Boolean> citeable,
            Optional<String> feedbackCode,
            Optional<Boolean> terminal,
            Optional<String> rejectedArguments) {
        static SessionMessageResponse from(SessionMessage message) {
            if (message instanceof UserMessage user) {
                return base(message, Optional.of(user.participantId()), Optional.of(user.message()), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty());
            }
            if (message instanceof AssistantMessage assistant) {
                return base(message, Optional.empty(), Optional.of(assistant.message()),
                        Optional.of(assistant.citations().stream().map(citation -> citation.value()).toList()), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            if (message instanceof ToolMessage tool) {
                return base(message, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(tool.resultId().value()),
                        Optional.of(tool.toolName()), Optional.of(tool.toolVersion()), tool.repositoryId(), tool.revision(), Optional.of(tool.citeable()),
                        Optional.empty(), Optional.empty(), Optional.empty());
            }
            FeedbackMessage feedback = (FeedbackMessage) message;
            return base(message, Optional.empty(), Optional.of(feedback.message()), Optional.empty(), Optional.empty(), feedback.toolName(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(feedback.code()), Optional.of(feedback.terminal()),
                    feedback.rejectedArguments());
        }

        private static SessionMessageResponse base(
                SessionMessage message,
                Optional<String> participantId,
                Optional<String> content,
                Optional<List<String>> citations,
                Optional<String> resultId,
                Optional<String> toolName,
                Optional<String> toolVersion,
                Optional<String> repositoryId,
                Optional<String> revision,
                Optional<Boolean> citeable,
                Optional<String> feedbackCode,
                Optional<Boolean> terminal,
                Optional<String> rejectedArguments) {
            return new SessionMessageResponse(message.sequence().value(), message.createdAt(), message.role().name(),
                    message.messageJobId().map(jobId -> jobId.value()), participantId, content, citations, resultId, toolName, toolVersion,
                    repositoryId, revision, citeable, feedbackCode, terminal, rejectedArguments);
        }
    }

    public record ResultResponse(
            String resultId,
            String sessionId,
            String toolName,
            String toolVersion,
            String canonicalArguments,
            Optional<String> repositoryId,
            Optional<String> revision,
            String resultJson,
            boolean citeable) {
        static ResultResponse from(ConversationResultView view) {
            return new ResultResponse(view.resultId(), view.sessionId(), view.toolName(), view.toolVersion(), view.canonicalArguments(),
                    view.repositoryId(), view.revision(), view.resultJson(), view.citeable());
        }
    }

    public record ErrorResponse(String code) {
    }
}
