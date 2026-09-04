package com.java.system.sessionagent.conversation.port.out;

import java.util.Objects;

public final class ConversationStoreFailure extends RuntimeException {

    public enum Kind {
        CONTRACT,
        INVALID_HISTORY,
        TRANSIENT
    }

    private final Kind kind;

    private ConversationStoreFailure(Kind kind, Throwable cause) {
        super("Conversation storage is unavailable", cause);
        this.kind = Objects.requireNonNull(kind, "Conversation store failure kind must not be null");
    }

    public static ConversationStoreFailure contract(Throwable cause) {
        return new ConversationStoreFailure(Kind.CONTRACT, cause);
    }

    public static ConversationStoreFailure transientFailure(Throwable cause) {
        return new ConversationStoreFailure(Kind.TRANSIENT, cause);
    }

    public static ConversationStoreFailure invalidHistory(Throwable cause) {
        return new ConversationStoreFailure(Kind.INVALID_HISTORY, cause);
    }

    public Kind kind() {
        return kind;
    }
}
