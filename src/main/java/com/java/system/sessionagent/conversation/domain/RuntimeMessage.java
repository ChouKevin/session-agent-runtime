package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record RuntimeMessage(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        String code,
        String message) implements SessionMessage {

    public RuntimeMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Runtime message must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.RUNTIME, "Message role must match RUNTIME message type");
        Assert.hasText(code, "Runtime message code must not be blank");
        Assert.hasText(message, "Runtime message must not be blank");
    }
}
