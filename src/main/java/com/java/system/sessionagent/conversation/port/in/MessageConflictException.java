package com.java.system.sessionagent.conversation.port.in;

public final class MessageConflictException extends RuntimeException {

    public MessageConflictException() {
        super("Incoming message conflicts with an existing source message");
    }
}
