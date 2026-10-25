package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.Optional;

public record ModelCallObservation(int totalOrdinal, Optional<CacheTokenUsage> cacheUsage) {
    public ModelCallObservation {
        Assert.isTrue(totalOrdinal > 0, "Model call ordinal must be positive");
        Assert.notNull(cacheUsage, "Cache usage availability must not be null");
    }
}
