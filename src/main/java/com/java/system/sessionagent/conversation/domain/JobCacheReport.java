package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public record JobCacheReport(
        Phase phase,
        Status status,
        int requestCount,
        int knownUsageRequestCount,
        long knownInputTokens,
        long knownCachedInputTokens,
        Optional<BigDecimal> hitRatio) {

    public JobCacheReport {
        Assert.notNull(phase, "Cache report phase must not be null");
        Assert.notNull(status, "Cache report status must not be null");
        Assert.notNull(hitRatio, "Cache report hit ratio availability must not be null");
        Assert.isTrue(requestCount >= 0 && knownUsageRequestCount >= 0 && knownUsageRequestCount <= requestCount,
                "Known usage request count must be between zero and request count");
        Assert.isTrue(knownInputTokens >= 0 && knownCachedInputTokens >= 0 && knownCachedInputTokens <= knownInputTokens,
                "Known cache usage must satisfy 0 <= cached input tokens <= input tokens");
        Assert.isTrue(knownUsageRequestCount > 0 || knownInputTokens == 0,
                "Unknown usage must not contain token sums");
    }

    public enum Phase {
        PROVISIONAL, FINAL
    }

    public enum Status {
        NOT_CALLED, UNAVAILABLE, PARTIAL, COMPLETE, NOT_APPLICABLE
    }

    public static JobCacheReport from(JobStatus status, int requestCount, int knownUsageRequestCount,
            long knownInputTokens, long knownCachedInputTokens) {
        Assert.notNull(status, "Job status must not be null");
        Phase phase = status == JobStatus.DONE || status == JobStatus.CANCELLED ? Phase.FINAL : Phase.PROVISIONAL;
        Status coverage;
        if (requestCount == 0) {
            coverage = Status.NOT_CALLED;
        } else if (knownUsageRequestCount == 0) {
            coverage = Status.UNAVAILABLE;
        } else if (knownUsageRequestCount < requestCount) {
            coverage = Status.PARTIAL;
        } else if (knownInputTokens == 0) {
            coverage = Status.NOT_APPLICABLE;
        } else {
            coverage = Status.COMPLETE;
        }
        Optional<BigDecimal> ratio = knownInputTokens == 0 ? Optional.empty()
                : Optional.of(BigDecimal.valueOf(knownCachedInputTokens)
                        .divide(BigDecimal.valueOf(knownInputTokens), 6, RoundingMode.HALF_UP));
        return new JobCacheReport(phase, coverage, requestCount, knownUsageRequestCount,
                knownInputTokens, knownCachedInputTokens, ratio);
    }
}
