package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.Optional;

public record ModelCallResult(ModelReply reply, Optional<ModelContinuation> continuation, ModelUsage usage) {

    public ModelCallResult {
        Assert.notNull(reply, "Model reply must not be null");
        Assert.notNull(continuation, "Model continuation must not be null");
        Assert.notNull(usage, "Model usage must not be null");
        Assert.isTrue(continuation.isEmpty() || reply instanceof ModelReply.UseTools,
                "Model continuation requires tool calls");
    }

    public ModelCallResult(ModelReply reply, Optional<ModelContinuation> continuation) {
        this(reply, continuation, new ModelUsage(0, 0, 0, false));
    }
}
