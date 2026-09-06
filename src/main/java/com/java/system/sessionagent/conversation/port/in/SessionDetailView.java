package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record SessionDetailView(
        String sessionId,
        Instant createdAt,
        Optional<MessageJobView> currentJob,
        Optional<CompactionBoundaryView> latestCompaction,
        ContextUsageView context) {

    public SessionDetailView {
        Assert.hasText(sessionId, "Session ID must not be blank");
        Assert.notNull(createdAt, "Session creation time must not be null");
        Assert.notNull(currentJob, "Current job must not be null");
        Assert.notNull(latestCompaction, "Latest compaction must not be null");
        Assert.notNull(context, "Context usage must not be null");
    }

    public record CompactionBoundaryView(long generation, long coveredThrough, String reason, Instant createdAt) {
        public CompactionBoundaryView {
            Assert.isTrue(generation > 0, "Compaction generation must be positive");
            Assert.isTrue(coveredThrough > 0, "Compaction boundary must be positive");
            Assert.hasText(reason, "Compaction reason must not be blank");
            Assert.notNull(createdAt, "Compaction creation time must not be null");
        }
    }

    public record ContextUsageView(String modelId, long capacityTokens, long estimatedUsedTokens, double ratio,
                                   ContextEstimate.Basis basis) {
        public ContextUsageView {
            Assert.hasText(modelId, "Active model ID must not be blank");
            Assert.isTrue(capacityTokens > 0, "Context capacity must be positive");
            Assert.isTrue(estimatedUsedTokens >= 0, "Estimated context usage must not be negative");
            Assert.isTrue(ratio >= 0, "Context usage ratio must not be negative");
            Assert.notNull(basis, "Context usage basis must not be null");
        }
    }
}
