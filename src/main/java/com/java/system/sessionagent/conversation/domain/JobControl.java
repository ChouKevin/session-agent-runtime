package com.java.system.sessionagent.conversation.domain;

import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;

public record JobControl(Mode mode, Stage stage, UserMessage initiatingMessage,
                         Optional<WaitingChoice> waiting, int ordinaryCallCount,
                         boolean intentAttempted, boolean wrapAttempted) {
    public enum Mode { NORMAL, REPORT_ONLY }
    public enum Stage { INTENT, EXECUTION, WRAP_UP }

    public JobControl {
        Objects.requireNonNull(mode, "Job mode must not be null");
        Objects.requireNonNull(stage, "Job stage must not be null");
        Objects.requireNonNull(initiatingMessage, "Initiating message must not be null");
        Objects.requireNonNull(waiting, "Waiting choice must not be null");
        Assert.isTrue(ordinaryCallCount >= 0, "Ordinary call count must not be negative");
    }
}
