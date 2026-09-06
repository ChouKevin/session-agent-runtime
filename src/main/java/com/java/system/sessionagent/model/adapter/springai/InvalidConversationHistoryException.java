package com.java.system.sessionagent.model.adapter.springai;

public final class InvalidConversationHistoryException extends RuntimeException {

    public InvalidConversationHistoryException() {
        super("Conversation history contains an incomplete tool batch");
    }
}
