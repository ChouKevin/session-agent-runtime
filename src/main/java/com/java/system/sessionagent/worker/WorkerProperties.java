package com.java.system.sessionagent.worker;

import org.springframework.util.Assert;

import java.time.Duration;

public record WorkerProperties(Duration claimDuration, Duration renewalInterval) {

    public WorkerProperties() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    public WorkerProperties {
        Assert.notNull(claimDuration, "Claim duration must not be null");
        Assert.notNull(renewalInterval, "Renewal interval must not be null");
        Assert.isTrue(!claimDuration.isNegative() && !claimDuration.isZero(), "Claim duration must be positive");
        Assert.isTrue(!renewalInterval.isNegative() && !renewalInterval.isZero(), "Renewal interval must be positive");
        Assert.isTrue(renewalInterval.compareTo(claimDuration.dividedBy(2)) < 0,
                "Renewal interval must be shorter than half the claim duration");
        assertSchedulable(claimDuration, "Claim duration");
        assertSchedulable(renewalInterval, "Renewal interval");
    }

    private static void assertSchedulable(Duration duration, String name) {
        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", exception);
        }
    }
}
