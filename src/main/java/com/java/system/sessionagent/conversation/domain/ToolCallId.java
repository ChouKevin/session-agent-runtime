package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ToolCallId(String value) {

    public ToolCallId {
        Assert.hasText(value, "Tool call ID must not be blank");
    }
}
