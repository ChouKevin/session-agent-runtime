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
        Optional<String> rejectedArguments) implements SessionMessage {

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
        modelCallId.ifPresent(value -> Assert.hasText(value, "Model call ID must not be blank"));
        toolName.ifPresent(value -> Assert.hasText(value, "Tool name must not be blank"));
        rejectedArguments.ifPresent(value -> Assert.hasText(value, "Rejected arguments must not be blank"));
        if (modelCallId.isPresent() != toolName.isPresent()) {
            throw new IllegalArgumentException("Tool feedback requires model call ID and tool name together");
        }
        if (modelCallId.isPresent() && rejectedArguments.isEmpty()) {
            throw new IllegalArgumentException("Tool feedback requires rejected arguments");
        }
        if (modelCallId.isEmpty() && rejectedArguments.isPresent()) {
            throw new IllegalArgumentException("General feedback must not contain rejected arguments");
        }
    }
}
