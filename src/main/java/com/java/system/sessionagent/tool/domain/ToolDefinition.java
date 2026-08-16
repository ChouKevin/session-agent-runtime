package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

public record ToolDefinition(ToolName name, String version, String description, String inputSchema, ToolKind kind) {

    public ToolDefinition {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(version, "Tool version must not be blank");
        Assert.hasText(description, "Tool description must not be blank");
        Assert.hasText(inputSchema, "Tool input schema must not be blank");
        Assert.notNull(kind, "Tool kind must not be null");
    }
}
