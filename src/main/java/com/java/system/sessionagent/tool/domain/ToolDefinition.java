package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

public record ToolDefinition(ToolName name, String description, String inputSchema) {

    public ToolDefinition {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(description, "Tool description must not be blank");
        Assert.hasText(inputSchema, "Tool input schema must not be blank");
    }
}
