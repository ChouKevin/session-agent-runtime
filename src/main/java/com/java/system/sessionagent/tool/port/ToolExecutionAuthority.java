package com.java.system.sessionagent.tool.port;

import org.springframework.util.Assert;

/** Trusted worker lease fence, separate from stable invocation identity. */
public record ToolExecutionAuthority(String workerId, long claimNumber) {
    public ToolExecutionAuthority {
        Assert.hasText(workerId, "Worker ID must not be blank");
        Assert.isTrue(claimNumber > 0, "Claim number must be positive");
    }
}
