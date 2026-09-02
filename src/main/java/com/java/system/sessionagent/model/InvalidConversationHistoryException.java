package com.java.system.sessionagent.model;

public final class InvalidConversationHistoryException extends RuntimeException {

    public InvalidConversationHistoryException() {
        super("Conversation history contains an incomplete tool batch");
    }
}
