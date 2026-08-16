package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;

import java.time.Duration;
import java.util.Optional;

final class ConversationFailurePolicy {

    private ConversationFailurePolicy() {
    }

    static Failure correctable(FeedbackCode code) {
        return new Failure(Action.CORRECTABLE, code, Optional.empty());
    }

    static Failure model(ModelCallFailure failure) {
        return switch (failure.kind()) {
            case CORRECTABLE -> correctable(FeedbackCode.MODEL_OUTPUT_INVALID);
            case TRANSIENT -> new Failure(Action.RETRY, FeedbackCode.DEPENDENCY_UNAVAILABLE, Optional.empty());
            case CONTEXT_TOO_LARGE -> new Failure(Action.TERMINAL, FeedbackCode.CONTEXT_TOO_LARGE, Optional.empty());
            case TERMINAL -> new Failure(Action.TERMINAL, FeedbackCode.DEPENDENCY_INVALID_RESPONSE, Optional.empty());
        };
    }

    static Failure tool(ToolExecutionFailure failure) {
        return switch (failure.kind()) {
            case INVALID_INPUT -> correctable(FeedbackCode.INVALID_TOOL_INPUT);
            case INPUT_TOO_LARGE -> correctable(FeedbackCode.TOOL_INPUT_TOO_LARGE);
            case UNKNOWN_REPOSITORY -> correctable(FeedbackCode.UNKNOWN_REPOSITORY);
            case REVISION_CHANGED -> correctable(FeedbackCode.REVISION_CHANGED);
            case TRANSIENT -> new Failure(Action.RETRY, FeedbackCode.DEPENDENCY_UNAVAILABLE, failure.retryAfter());
            case FORBIDDEN -> new Failure(Action.TERMINAL, FeedbackCode.DEPENDENCY_FORBIDDEN, Optional.empty());
            case INVALID_RESPONSE -> new Failure(Action.TERMINAL, FeedbackCode.DEPENDENCY_INVALID_RESPONSE, Optional.empty());
        };
    }

    enum Action { CORRECTABLE, RETRY, TERMINAL }

    record Failure(Action action, FeedbackCode code, Optional<Duration> retryAfter) {
    }
}
