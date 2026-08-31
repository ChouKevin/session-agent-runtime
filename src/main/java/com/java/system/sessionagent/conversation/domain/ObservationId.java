package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record ObservationId(String value) {

    public ObservationId {
        Assert.hasText(value, "Observation ID must not be blank");
    }
}
