package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record FeedbackMessage(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        String code,
        String message,
        boolean terminal,
        Optional<String> modelCallId,
        Optional<String> toolName,
        Optional<String> rejectedArguments,
        Optional<String> modelContext) implements SessionMessage {

    public FeedbackMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Feedback message must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.FEEDBACK, "Message role must match FEEDBACK message type");
        Assert.hasText(code, "Feedback code must not be blank");
        Assert.hasText(message, "Feedback message must not be blank");
        Assert.notNull(modelCallId, "Model call ID must not be null");
        Assert.notNull(toolName, "Tool name must not be null");
        Assert.notNull(rejectedArguments, "Rejected arguments must not be null");
        Assert.notNull(modelContext, "Model context must not be null");
        modelCallId.ifPresent(value -> Assert.hasText(value, "Model call ID must not be blank"));
        toolName.ifPresent(value -> Assert.hasText(value, "Tool name must not be blank"));
        rejectedArguments.ifPresent(value -> Assert.hasText(value, "Rejected arguments must not be blank"));
        modelContext.ifPresent(value -> Assert.hasText(value, "Model context must not be blank"));
        boolean toolFeedback = modelCallId.isPresent()
                && toolName.isPresent()
                && rejectedArguments.isPresent()
                && modelContext.isPresent();
        boolean generalFeedback = modelCallId.isEmpty()
                && toolName.isEmpty()
                && rejectedArguments.isEmpty()
                && modelContext.isEmpty();
        if (!toolFeedback && !generalFeedback) {
            throw new IllegalArgumentException("Tool feedback requires complete model call context");
        }
    }
}
