package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record MessageReceipt(SessionId sessionId, MessageJobId messageJobId) {

    public MessageReceipt {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
    }
}
