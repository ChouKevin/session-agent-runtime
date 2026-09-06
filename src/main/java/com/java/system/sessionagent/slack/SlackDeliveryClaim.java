package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.UUID;

public record SlackDeliveryClaim(
        UUID deliveryId,
        UUID sessionId,
        UUID messageJobId,
        long claimNumber,
        int attemptCount,
        String workerId,
        Instant lockedUntil,
        SlackPostRequest postRequest) {

    public SlackDeliveryClaim {
        Assert.notNull(deliveryId, "Slack delivery ID must not be null");
        Assert.notNull(sessionId, "Slack delivery session ID must not be null");
        Assert.notNull(messageJobId, "Slack delivery message job ID must not be null");
        Assert.isTrue(claimNumber > 0, "Slack delivery claim number must be positive");
        Assert.isTrue(attemptCount > 0, "Slack delivery attempt count must be positive");
        Assert.hasText(workerId, "Slack delivery worker ID must not be blank");
        Assert.notNull(lockedUntil, "Slack delivery lock time must not be null");
        Assert.notNull(postRequest, "Slack post request must not be null");
    }
}
