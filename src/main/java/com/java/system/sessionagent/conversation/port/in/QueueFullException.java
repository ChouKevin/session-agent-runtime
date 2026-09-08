package com.java.system.sessionagent.conversation.port.in;

public final class QueueFullException extends RuntimeException {

    public QueueFullException() {
        super("Unfinished job capacity is full");
    }
}
