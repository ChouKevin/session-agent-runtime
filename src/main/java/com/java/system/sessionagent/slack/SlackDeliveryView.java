package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record SlackDeliveryView(
        UUID deliveryId,
        SlackDeliveryStatus status,
        int attemptCount,
        Optional<SlackDeliveryFailureCategory> failureCategory,
        Optional<String> slackMessageTs,
        Instant nextAttemptAt) {

    public SlackDeliveryView {
        Assert.notNull(deliveryId, "Slack delivery ID must not be null");
        Assert.notNull(status, "Slack delivery status must not be null");
        Assert.isTrue(attemptCount >= 0, "Slack delivery attempt count must not be negative");
        Assert.notNull(failureCategory, "Slack delivery failure category must not be null");
        Assert.notNull(slackMessageTs, "Slack delivery Slack message timestamp must not be null");
        Assert.notNull(nextAttemptAt, "Slack delivery next attempt time must not be null");
    }
}
