package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record SessionSequence(long value) {

    public SessionSequence {
        Assert.isTrue(value > 0, "Session sequence must be positive");
    }
}
