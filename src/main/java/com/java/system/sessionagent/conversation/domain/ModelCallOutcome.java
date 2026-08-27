package com.java.system.sessionagent.conversation.domain;

public enum ModelCallOutcome {
    TOOL_CALL,
    ANSWER_READY,
    FINAL_REPLY,
    INVALID_RESPONSE,
    PROVIDER_FAILURE
}
