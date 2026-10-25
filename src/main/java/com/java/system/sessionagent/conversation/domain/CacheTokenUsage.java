package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record CacheTokenUsage(long inputTokens, long cachedInputTokens) {
    public CacheTokenUsage {
        Assert.isTrue(inputTokens >= 0 && cachedInputTokens >= 0 && cachedInputTokens <= inputTokens,
                "Cache usage must satisfy 0 <= cached input tokens <= input tokens");
    }
}
