package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record MessageJobId(String value) {

    public MessageJobId {
        Assert.hasText(value, "Message job ID must not be blank");
    }
}
