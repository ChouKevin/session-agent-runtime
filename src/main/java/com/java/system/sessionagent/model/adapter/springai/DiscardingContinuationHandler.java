package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.Optional;

public final class DiscardingContinuationHandler implements SpringAiContinuationHandler {
    private final ModelRouteId routeId;

    public DiscardingContinuationHandler(ModelRouteId routeId) {
        Assert.notNull(routeId, "Model route ID must not be null");
        this.routeId = routeId;
    }

    @Override
    public ModelRouteId routeId() {
        return routeId;
    }

    @Override
    public Optional<ModelContinuation> capture(AssistantMessage message) {
        Assert.notNull(message, "Provider assistant message must not be null");
        return Optional.empty();
    }

    @Override
    public Map<String, Object> restore(ModelContinuation continuation) {
        Assert.notNull(continuation, "Model continuation must not be null");
        return Map.of();
    }
}
