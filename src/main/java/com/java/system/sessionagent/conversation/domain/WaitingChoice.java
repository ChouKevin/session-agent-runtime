package com.java.system.sessionagent.conversation.domain;

import java.util.Objects;
import org.springframework.util.Assert;

public record WaitingChoice(MessageJobId originJob, SessionSequence responseSequence, long generation) {
    public WaitingChoice {
        Objects.requireNonNull(originJob, "Waiting origin job must not be null");
        Objects.requireNonNull(responseSequence, "Waiting response sequence must not be null");
        Assert.isTrue(generation > 0, "Waiting generation must be positive");
    }
}
