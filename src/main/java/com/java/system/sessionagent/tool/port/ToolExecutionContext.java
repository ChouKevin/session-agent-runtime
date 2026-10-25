package com.java.system.sessionagent.tool.port;

import java.util.Objects;
import java.util.UUID;
import org.springframework.util.Assert;

/** Stable invocation identity, not lease authority. */
public record ToolExecutionContext(UUID sessionId, UUID messageJobId, long modelCallNumber, int toolOrdinal) {
    public ToolExecutionContext {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(messageJobId);
        Assert.isTrue(modelCallNumber > 0 && toolOrdinal >= 0, "Invalid tool invocation identity");
    }
}
