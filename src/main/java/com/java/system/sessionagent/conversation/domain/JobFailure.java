package com.java.system.sessionagent.conversation.domain;

public enum JobFailure {
    MODEL_CALL_LIMIT_REACHED("Runtime model call limit reached."),
    MODEL_OUTPUT_INVALID("Runtime model output is invalid."),
    MODEL_UNAVAILABLE("Runtime model is unavailable."),
    CONTEXT_TOO_LARGE("Runtime model context is too large."),
    COMPACTION_FAILED("Runtime context compaction failed."),
    INVALID_CONVERSATION_HISTORY("Runtime conversation history is invalid."),
    STORAGE_UNAVAILABLE("Conversation storage is unavailable."),
    PROCESSING_INTERRUPTED("Processing was interrupted and is awaiting recovery.");

    private final String message;

    JobFailure(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
