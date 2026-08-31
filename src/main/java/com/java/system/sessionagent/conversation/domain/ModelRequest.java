package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.application.ToolSnapshot;
import org.springframework.util.Assert;

import java.util.List;

public record ModelRequest(List<SessionMessage> history, ToolSnapshot toolSnapshot) {

    public ModelRequest {
        history = List.copyOf(history);
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
    }
}
