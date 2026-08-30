package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record AssistantMessage(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        String message) implements SessionMessage {

    public AssistantMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Assistant message must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.ASSISTANT, "Message role must match ASSISTANT message type");
        Assert.hasText(message, "Assistant message must not be blank");
    }
}
