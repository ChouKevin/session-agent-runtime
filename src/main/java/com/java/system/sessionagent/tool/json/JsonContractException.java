package com.java.system.sessionagent.tool.json;

public final class JsonContractException extends RuntimeException {

    private final Reason reason;

    public JsonContractException() {
        this(Reason.INVALID_JSON_CONTRACT);
    }

    private JsonContractException(Reason reason) {
        super("Invalid JSON contract");
        this.reason = reason;
    }

    static JsonContractException inputTooLarge() {
        return new JsonContractException(Reason.INPUT_TOO_LARGE);
    }

    public boolean isInputTooLarge() {
        return reason == Reason.INPUT_TOO_LARGE;
    }

    private enum Reason {
        INVALID_JSON_CONTRACT,
        INPUT_TOO_LARGE
    }
}
