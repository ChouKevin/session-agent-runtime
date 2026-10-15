package com.java.system.sessionagent.conversation.port.in;

public enum MessageJobProcessingResult {
    COMPLETED,
    CANCELLED,
    RETRY_SCHEDULED,
    OWNERSHIP_LOST,
    STATE_UNCONFIRMED
}
