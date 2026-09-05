package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolDefinition;
import org.springframework.util.Assert;

import java.util.List;

public record ContextUsageProjection(
        ModelDescriptor model,
        String systemPrompt,
        List<ToolDefinition> toolDefinitions,
        List<SessionMessage> history,
        long compactGeneration) {

    public ContextUsageProjection {
        Assert.notNull(model, "Context model must not be null");
        Assert.hasText(systemPrompt, "Context system prompt must not be blank");
        Assert.notNull(toolDefinitions, "Context tool definitions must not be null");
        toolDefinitions = List.copyOf(toolDefinitions);
        Assert.notNull(history, "Context history must not be null");
        history = List.copyOf(history);
        Assert.isTrue(compactGeneration >= 0, "Context compact generation must not be negative");
    }
}
