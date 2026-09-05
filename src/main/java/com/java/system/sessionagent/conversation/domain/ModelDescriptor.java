package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ModelDescriptor(ModelRouteId routeId, String modelId, long contextWindowTokens) {

    public ModelDescriptor {
        Assert.notNull(routeId, "Model route ID must not be null");
        Assert.hasText(modelId, "Model ID must not be blank");
        Assert.isTrue(contextWindowTokens > 0, "Model context window tokens must be positive");
    }
}
