package com.java.system.sessionagent.worker.configuration;

import org.springframework.util.Assert;

import java.time.Duration;

public record WorkerProperties(Duration claimDuration, Duration renewalInterval, Duration pollDelay, int concurrency) {

    public WorkerProperties() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1), 4);
    }

    public WorkerProperties(Duration claimDuration, Duration renewalInterval) {
        this(claimDuration, renewalInterval, Duration.ofSeconds(1), 4);
    }

    public WorkerProperties {
        Assert.notNull(claimDuration, "Claim duration must not be null");
        Assert.notNull(renewalInterval, "Renewal interval must not be null");
        Assert.notNull(pollDelay, "Poll delay must not be null");
        Assert.isTrue(!claimDuration.isNegative() && !claimDuration.isZero(), "Claim duration must be positive");
        Assert.isTrue(!renewalInterval.isNegative() && !renewalInterval.isZero(), "Renewal interval must be positive");
        Assert.isTrue(!pollDelay.isNegative() && !pollDelay.isZero(), "Poll delay must be positive");
        Assert.isTrue(concurrency > 0, "Worker concurrency must be positive");
        Assert.isTrue(renewalInterval.compareTo(claimDuration.dividedBy(2)) < 0,
                "Renewal interval must be shorter than half the claim duration");
        assertSchedulable(claimDuration, "Claim duration");
        assertSchedulable(renewalInterval, "Renewal interval");
        assertSchedulable(pollDelay, "Poll delay");
    }

    private static void assertSchedulable(Duration duration, String name) {
        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", exception);
        }
    }
}
