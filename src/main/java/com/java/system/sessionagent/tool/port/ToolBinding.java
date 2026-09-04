package com.java.system.sessionagent.tool.port;

import org.springframework.util.Assert;

public record ToolBinding(ToolDefinition definition, ToolExecutor executor) {

    public ToolBinding {
        Assert.notNull(definition, "Tool definition must not be null");
        Assert.notNull(executor, "Tool executor must not be null");
    }
}
