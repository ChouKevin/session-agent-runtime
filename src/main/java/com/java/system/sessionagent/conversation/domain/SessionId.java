package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record SessionId(String value) {

    public SessionId {
        Assert.hasText(value, "Session ID must not be blank");
    }
}
