package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;

public record ContextUsageCheckpoint(
        ModelDescriptor model,
        int modelCallOrdinal,
        SessionSequence responseBoundary,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        String requestShapeFingerprint,
        long compactGeneration,
        Instant createdAt) {

    public ContextUsageCheckpoint {
        Assert.notNull(model, "Checkpoint model must not be null");
        Assert.isTrue(modelCallOrdinal > 0, "Checkpoint model call ordinal must be positive");
        Assert.notNull(responseBoundary, "Checkpoint response boundary must not be null");
        Assert.isTrue(promptTokens >= 0, "Checkpoint prompt tokens must not be negative");
        Assert.isTrue(completionTokens >= 0, "Checkpoint completion tokens must not be negative");
        Assert.isTrue(totalTokens >= 0, "Checkpoint total tokens must not be negative");
        Assert.hasText(requestShapeFingerprint, "Checkpoint request shape fingerprint must not be blank");
        Assert.isTrue(compactGeneration >= 0, "Checkpoint compact generation must not be negative");
        Assert.notNull(createdAt, "Checkpoint creation time must not be null");
    }
}
