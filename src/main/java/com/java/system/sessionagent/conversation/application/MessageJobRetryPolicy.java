package com.java.system.sessionagent.conversation.application;

import org.springframework.util.Assert;

import java.time.Duration;

public record MessageJobRetryPolicy(int transientRetries, Duration maximumBackoff) {

    public MessageJobRetryPolicy {
        Assert.isTrue(transientRetries >= 0, "Transient retries must not be negative");
        Assert.notNull(maximumBackoff, "Maximum backoff must not be null");
        Assert.isTrue(!maximumBackoff.isNegative() && !maximumBackoff.isZero(), "Maximum backoff must be positive");
    }
}
