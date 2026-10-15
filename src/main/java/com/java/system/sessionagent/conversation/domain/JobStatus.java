package com.java.system.sessionagent.conversation.domain;

public enum JobStatus {
    PENDING,
    WORKING,
    RETRY,
    DONE,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}
