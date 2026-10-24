package com.java.system.sessionagent.slack.domain;

import java.util.Objects;
import java.util.UUID;
import org.springframework.util.Assert;

public record SlackProgressClaim(UUID messageJobId, String workerId, long claimNumber) {
    public SlackProgressClaim {
        Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        Assert.hasText(workerId, "Progress worker ID must not be blank");
        Assert.isTrue(claimNumber > 0, "Claim number must be positive");
    }
}
