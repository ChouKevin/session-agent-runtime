package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

/** Model-visible, operational context; it is deliberately not a public conversation message. */
public record ContextSummary(String text) {

    public ContextSummary {
        Assert.hasText(text, "Context summary must not be blank");
    }
}
