package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record ToolObservation(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        ToolCallId toolCallId,
        String toolName,
        Object output) implements SessionMessage {

    public ToolObservation {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Tool observation must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.TOOL, "Message role must match TOOL message type");
        Assert.notNull(toolCallId, "Tool call ID must not be null");
        Assert.hasText(toolName, "Tool name must not be blank");
        Assert.notNull(output, "Tool output must not be null");
    }
}
