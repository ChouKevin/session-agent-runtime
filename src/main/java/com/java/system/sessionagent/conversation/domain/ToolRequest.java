package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

public record ToolRequest(ToolName toolName, String input) {

    public ToolRequest {
        Assert.notNull(toolName, "Tool name must not be null");
        Assert.notNull(input, "Tool input must not be null");
    }
}
