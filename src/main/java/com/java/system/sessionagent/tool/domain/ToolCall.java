package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

public record ToolCall(ToolName name, String arguments) {

    public ToolCall {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(arguments, "Tool arguments must not be blank");
    }
}
