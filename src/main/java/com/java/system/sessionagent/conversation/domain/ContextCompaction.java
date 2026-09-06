package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;

/** Immutable operational checkpoint describing the model-visible compacted prefix. */
public record ContextCompaction(
        long generation,
        MessageJobId messageJobId,
        Reason reason,
        ContextSummary summary,
        SessionSequence coveredThrough,
        ModelDescriptor model,
        String requestShapeFingerprint,
        long estimateBeforeTokens,
        long estimateAfterTokens,
        Instant createdAt) {

    public ContextCompaction {
        Assert.isTrue(generation > 0, "Context compaction generation must be positive");
        Assert.notNull(messageJobId, "Context compaction job ID must not be null");
        Assert.notNull(reason, "Context compaction reason must not be null");
        Assert.notNull(summary, "Context compaction summary must not be null");
        Assert.notNull(coveredThrough, "Context compaction boundary must not be null");
        Assert.notNull(model, "Context compaction model must not be null");
        Assert.hasText(requestShapeFingerprint, "Context compaction fingerprint must not be blank");
        Assert.isTrue(estimateBeforeTokens >= 0, "Context compaction input estimate must not be negative");
        Assert.isTrue(estimateAfterTokens >= 0, "Context compaction output estimate must not be negative");
        Assert.notNull(createdAt, "Context compaction creation time must not be null");
    }

    public enum Reason {
        THRESHOLD,
        OVERFLOW
    }
}
