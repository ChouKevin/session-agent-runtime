package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record ToolMessage(
        SessionId sessionId,
        SessionSequence sequence,
        Optional<MessageJobId> messageJobId,
        Instant createdAt,
        MessageRole role,
        ResultId resultId,
        String modelCallId,
        String modelContext,
        String toolName,
        String toolVersion,
        String arguments,
        Optional<String> repositoryId,
        Optional<String> revision,
        String resultJson) implements SessionMessage {

    public ToolMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Session sequence must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Tool message must belong to a message job");
        Assert.notNull(createdAt, "Message creation time must not be null");
        Assert.isTrue(role == MessageRole.TOOL, "Message role must match TOOL message type");
        Assert.notNull(resultId, "Result ID must not be null");
        Assert.hasText(modelCallId, "Model call ID must not be blank");
        Assert.hasText(modelContext, "Model context must not be blank");
        Assert.hasText(toolName, "Tool name must not be blank");
        Assert.hasText(toolVersion, "Tool version must not be blank");
        Assert.hasText(arguments, "Tool arguments must not be blank");
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.notNull(revision, "Revision must not be null");
        repositoryId.ifPresent(value -> Assert.hasText(value, "Repository ID must not be blank"));
        revision.ifPresent(value -> Assert.hasText(value, "Revision must not be blank"));
        Assert.isTrue(repositoryId.isPresent() == revision.isPresent(),
                "Repository ID and revision must be present together");
        Assert.hasText(resultJson, "Tool result JSON must not be blank");
    }
}
