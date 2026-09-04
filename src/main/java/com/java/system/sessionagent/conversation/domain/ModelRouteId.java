package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ModelRouteId(String value) {

    public ModelRouteId {
        Assert.hasText(value, "Model route ID must not be blank");
        Assert.isTrue(value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"), "Model route ID is not portable");
    }
}
