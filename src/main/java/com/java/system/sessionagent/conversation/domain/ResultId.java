package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ResultId(String value) {

    public ResultId {
        Assert.hasText(value, "Result ID must not be blank");
    }
}
