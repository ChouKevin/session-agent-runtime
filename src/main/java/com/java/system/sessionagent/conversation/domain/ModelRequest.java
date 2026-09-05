package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ModelRequest(
        List<SessionMessage> history,
        Map<SessionSequence, ModelContinuation> continuations,
        ToolSnapshot toolSnapshot,
        Optional<ContextSummary> contextSummary) {

    public ModelRequest {
        Assert.notNull(history, "Model history must not be null");
        history = List.copyOf(history);
        Assert.notNull(continuations, "Model continuations must not be null");
        continuations = Map.copyOf(continuations);
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
        Assert.notNull(contextSummary, "Context summary must not be null");
    }

    public ModelRequest(List<SessionMessage> history, Map<SessionSequence, ModelContinuation> continuations, ToolSnapshot toolSnapshot) {
        this(history, continuations, toolSnapshot, Optional.empty());
    }

    public ModelRequest(List<SessionMessage> history, ToolSnapshot toolSnapshot) {
        this(history, Map.of(), toolSnapshot);
    }
}
