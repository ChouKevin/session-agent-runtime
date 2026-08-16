package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolDefinition;
import org.springframework.util.Assert;

public record ToolRegistration<T>(ToolDefinition definition, Class<T> inputType, ToolExecutor<T> executor) {

    public ToolRegistration {
        Assert.notNull(definition, "Tool definition must not be null");
        Assert.notNull(inputType, "Tool input type must not be null");
        Assert.notNull(executor, "Tool executor must not be null");
    }
}
