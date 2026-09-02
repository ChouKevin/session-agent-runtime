package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.springframework.util.Assert;

import java.util.List;

public record ModelRequest(List<SessionMessage> history, ToolSnapshot toolSnapshot) {

    public ModelRequest {
        Assert.notNull(history, "Model history must not be null");
        history = List.copyOf(history);
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
    }
}
