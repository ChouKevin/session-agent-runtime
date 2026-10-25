package com.java.system.sessionagent.conversation.domain;

public enum ModelCallPurpose {
    EXECUTION, COMPACTION, INTENT, WRAP_UP;

    public boolean ordinary() {
        return this == EXECUTION || this == COMPACTION;
    }
}
