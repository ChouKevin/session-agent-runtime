package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record AssistantToolCallsMessage(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        Optional<String> message,
        List<ToolRequest> requests) implements SessionMessage {

    public AssistantToolCallsMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Assistant tool calls must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.ASSISTANT_TOOL_CALLS, "Message role must match assistant tool calls");
        Assert.notNull(message, "Assistant tool call message must not be null");
        message.ifPresent(value -> Assert.hasText(value, "Assistant tool call message must not be blank"));
        Assert.notEmpty(requests, "Assistant tool calls must not be empty");
        requests = List.copyOf(requests);
    }
}
