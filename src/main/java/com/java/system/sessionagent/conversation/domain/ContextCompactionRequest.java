package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;

/** Provider-neutral input for a no-tools historical summarization call. */
public record ContextCompactionRequest(Optional<ContextSummary> previousSummary, List<SessionMessage> history) {

    public ContextCompactionRequest {
        Assert.notNull(previousSummary, "Previous context summary must not be null");
        Assert.notNull(history, "Context compaction history must not be null");
        history = List.copyOf(history);
        Assert.notEmpty(history, "Context compaction history must not be empty");
    }
}
