package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ContextEstimate(long tokens, Basis basis) {

    public ContextEstimate {
        Assert.isTrue(tokens >= 0, "Context estimate tokens must not be negative");
        Assert.notNull(basis, "Context estimate basis must not be null");
    }

    public enum Basis {
        PROVIDER_PLUS_TRAILING_ESTIMATE,
        FULL_ESTIMATE
    }
}
