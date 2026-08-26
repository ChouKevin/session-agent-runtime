package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.List;

public record ReplyRequest(List<SessionMessage> history, ModelCallContext callContext) {

    public ReplyRequest {
        history = List.copyOf(history);
        Assert.notNull(callContext, "Model call context must not be null");
    }
}
