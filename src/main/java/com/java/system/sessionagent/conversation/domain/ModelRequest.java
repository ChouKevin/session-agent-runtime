package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;

public record ModelRequest(
        List<SessionMessage> history,
        Map<SessionSequence, ModelContinuation> continuations,
        ToolSnapshot toolSnapshot) {

    public ModelRequest {
        Assert.notNull(history, "Model history must not be null");
        history = List.copyOf(history);
        Assert.notNull(continuations, "Model continuations must not be null");
        continuations = Map.copyOf(continuations);
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
    }

    public ModelRequest(List<SessionMessage> history, ToolSnapshot toolSnapshot) {
        this(history, Map.of(), toolSnapshot);
    }
}
