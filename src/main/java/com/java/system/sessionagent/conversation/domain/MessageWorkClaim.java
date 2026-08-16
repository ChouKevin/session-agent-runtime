package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.time.Instant;

public record MessageWorkClaim(
        MessageJobId messageJobId,
        SessionId sessionId,
        String workerId,
        long claimNumber,
        Instant claimedAt,
        Instant lockedUntil) {

    public MessageWorkClaim {
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.hasText(workerId, "Worker ID must not be blank");
        Assert.isTrue(claimNumber > 0, "Claim number must be positive");
        Assert.notNull(claimedAt, "Claim time must not be null");
        Assert.notNull(lockedUntil, "Lock expiry must not be null");
        Assert.isTrue(lockedUntil.isAfter(claimedAt), "Lock expiry must be after claim time");
    }
}
