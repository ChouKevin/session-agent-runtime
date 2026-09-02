package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolRequest(ToolCallId toolCallId, ToolName toolName, Map<String, Object> arguments) {

    public ToolRequest {
        Assert.notNull(toolCallId, "Tool call ID must not be null");
        Assert.notNull(toolName, "Tool name must not be null");
        Assert.notNull(arguments, "Tool arguments must not be null");
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
