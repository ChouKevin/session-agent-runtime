package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

import java.time.Duration;

public record SlackDeliveryProperties(
        Duration leaseDuration,
        Duration initialBackoff,
        Duration maximumBackoff,
        int maximumAttempts) {

    public SlackDeliveryProperties {
        Assert.notNull(leaseDuration, "Slack delivery lease duration must not be null");
        Assert.notNull(initialBackoff, "Slack delivery initial backoff must not be null");
        Assert.notNull(maximumBackoff, "Slack delivery maximum backoff must not be null");
        Assert.isTrue(!leaseDuration.isNegative() && !leaseDuration.isZero(), "Slack delivery lease duration must be positive");
        Assert.isTrue(!initialBackoff.isNegative() && !initialBackoff.isZero(), "Slack delivery initial backoff must be positive");
        Assert.isTrue(!maximumBackoff.isNegative() && !maximumBackoff.isZero(), "Slack delivery maximum backoff must be positive");
        Assert.isTrue(initialBackoff.compareTo(maximumBackoff) <= 0,
                "Slack delivery initial backoff must not exceed maximum backoff");
        Assert.isTrue(maximumAttempts > 0, "Slack delivery maximum attempts must be positive");
    }

    public SlackDeliveryProperties() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(60), 5);
    }

    public SlackDeliveryProperties(Duration leaseDuration, Duration maximumBackoff, int maximumAttempts) {
        this(leaseDuration, Duration.ofSeconds(1), maximumBackoff, maximumAttempts);
    }
}
