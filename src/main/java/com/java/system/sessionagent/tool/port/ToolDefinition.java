package com.java.system.sessionagent.tool.port;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolDefinition(ToolName name, String description, Map<String, Object> inputSchema) {

    public static ToolDefinition fromExposedName(String exposedName, String description, Map<String, Object> inputSchema) {
        return new ToolDefinition(new ToolName(exposedName), description, inputSchema);
    }

    public ToolDefinition {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(description, "Tool description must not be blank");
        Assert.notNull(inputSchema, "Tool input schema must not be null");
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
