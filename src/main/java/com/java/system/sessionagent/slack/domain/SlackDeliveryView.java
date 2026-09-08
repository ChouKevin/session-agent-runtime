package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record SlackDeliveryView(
        UUID deliveryId,
        SlackDeliveryKind kind,
        SlackDeliveryStatus status,
        int attemptCount,
        Optional<SlackDeliveryFailureCategory> failureCategory,
        Optional<String> slackMessageTs,
        Instant nextAttemptAt,
        Optional<UUID> messageJobId,
        Optional<Integer> totalParts,
        int sentParts) {

    public SlackDeliveryView(
            UUID deliveryId,
            SlackDeliveryKind kind,
            SlackDeliveryStatus status,
            int attemptCount,
            Optional<SlackDeliveryFailureCategory> failureCategory,
            Optional<String> slackMessageTs,
            Instant nextAttemptAt,
            Optional<UUID> messageJobId) {
        this(deliveryId, kind, status, attemptCount, failureCategory, slackMessageTs, nextAttemptAt, messageJobId,
                Optional.empty(), 0);
    }

    public SlackDeliveryView {
        Assert.notNull(deliveryId, "Slack delivery ID must not be null");
        Assert.notNull(kind, "Slack delivery kind must not be null");
        Assert.notNull(status, "Slack delivery status must not be null");
        Assert.isTrue(attemptCount >= 0, "Slack delivery attempt count must not be negative");
        Assert.notNull(failureCategory, "Slack delivery failure category must not be null");
        Assert.notNull(slackMessageTs, "Slack delivery Slack message timestamp must not be null");
        Assert.notNull(nextAttemptAt, "Slack delivery next attempt time must not be null");
        Assert.notNull(messageJobId, "Slack delivery job ID must not be null");
        Assert.notNull(totalParts, "Slack delivery total parts must not be null");
        Assert.isTrue(sentParts >= 0, "Slack delivery sent parts must not be negative");
        totalParts.ifPresent(total -> Assert.isTrue(total >= sentParts, "Slack delivery sent parts must not exceed total parts"));
    }
}
