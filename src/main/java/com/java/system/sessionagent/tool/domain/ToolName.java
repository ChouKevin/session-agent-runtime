package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

public record ToolName(String value) {

    public ToolName {
        Assert.hasText(value, "Tool name must not be blank");
    }
}
