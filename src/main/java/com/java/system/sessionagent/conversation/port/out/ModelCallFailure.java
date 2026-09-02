package com.java.system.sessionagent.conversation.port.out;

public final class ModelCallFailure extends RuntimeException {

    public enum Kind {
        CORRECTABLE,
        TRANSIENT,
        CONTEXT_TOO_LARGE,
        INVALID_HISTORY,
        TERMINAL
    }

    private final Kind kind;

    private ModelCallFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static ModelCallFailure correctable() {
        return new ModelCallFailure(Kind.CORRECTABLE, "Model output needs correction");
    }

    public static ModelCallFailure transientFailure() {
        return new ModelCallFailure(Kind.TRANSIENT, "Model provider is temporarily unavailable");
    }

    public static ModelCallFailure terminal() {
        return new ModelCallFailure(Kind.TERMINAL, "Model provider rejected the request");
    }

    public static ModelCallFailure contextTooLarge() {
        return new ModelCallFailure(Kind.CONTEXT_TOO_LARGE, "Model context is too large");
    }

    public static ModelCallFailure invalidHistory() {
        return new ModelCallFailure(Kind.INVALID_HISTORY, "Conversation history is invalid");
    }

    public Kind kind() {
        return kind;
    }
}
