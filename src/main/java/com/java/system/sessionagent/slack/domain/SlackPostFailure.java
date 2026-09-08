package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Optional;

public final class SlackPostFailure extends RuntimeException {

    private final SlackDeliveryFailureCategory category;
    private final Optional<Duration> retryAfter;
    private final Optional<String> slackMessageTs;

    public SlackPostFailure(SlackDeliveryFailureCategory category, Optional<Duration> retryAfter) {
        this(category, retryAfter, Optional.empty());
    }

    public SlackPostFailure(
            SlackDeliveryFailureCategory category,
            Optional<Duration> retryAfter,
            Optional<String> slackMessageTs) {
        this.category = java.util.Objects.requireNonNull(category, "Slack failure category must not be null");
        this.retryAfter = java.util.Objects.requireNonNull(retryAfter, "Slack retry-after must not be null");
        this.slackMessageTs = java.util.Objects.requireNonNull(slackMessageTs, "Slack message timestamp must not be null");
        Assert.isTrue(category != SlackDeliveryFailureCategory.RATE_LIMIT || retryAfter.isPresent(),
                "Rate limited Slack failures must have retry-after");
        retryAfter.ifPresent(delay -> Assert.isTrue(!delay.isNegative(), "Slack retry-after must not be negative"));
    }

    public SlackDeliveryFailureCategory category() {
        return category;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    public Optional<String> slackMessageTs() {
        return slackMessageTs;
    }
}
