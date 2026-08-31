package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ModelCallContext(SessionId sessionId, MessageJobId messageJobId, int ordinal) {

    public ModelCallContext {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(ordinal >= 1, "Model call ordinal must be positive");
    }
}
