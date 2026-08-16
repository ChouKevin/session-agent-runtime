package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ModelUsage(long promptTokens, long completionTokens, long totalTokens, boolean available) {

    public ModelUsage {
        Assert.isTrue(promptTokens >= 0, "Prompt token count must not be negative");
        Assert.isTrue(completionTokens >= 0, "Completion token count must not be negative");
        Assert.isTrue(totalTokens >= 0, "Total token count must not be negative");
        if (!available && (promptTokens != 0 || completionTokens != 0 || totalTokens != 0)) {
            throw new IllegalArgumentException("Unavailable model usage must not contain token counts");
        }
    }

}
