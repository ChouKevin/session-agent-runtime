package com.java.system.sessionagent.tool.application;

import org.springframework.util.Assert;

/** Formats typed tool failures as model-safe, provider-neutral observations. */
public final class ToolFailureOutput {

    private ToolFailureOutput() {
    }

    public static String format(ToolExecutionFailure failure) {
        Assert.notNull(failure, "Tool execution failure must not be null");
        return "Tool execution failed.\nCode: " + codeFor(failure.kind()) + "\nMessage: " + messageFor(failure);
    }

    private static String codeFor(ToolExecutionFailure.Kind kind) {
        return switch (kind) {
            case INVALID_INPUT -> "TOOL_INPUT_INVALID";
            case INPUT_TOO_LARGE -> "TOOL_INPUT_TOO_LARGE";
            case REPOSITORY_NOT_FOUND -> "TOOL_REPOSITORY_NOT_FOUND";
            case REVISION_OUTDATED -> "TOOL_REVISION_OUTDATED";
            case INDEX_NOT_READY -> "TOOL_INDEX_NOT_READY";
            case INDEX_CONTRACT_MISMATCH -> "TOOL_INDEX_CONTRACT_MISMATCH";
            case CODE_FACT_NOT_FOUND -> "TOOL_CODE_FACT_NOT_FOUND";
            case CODE_FACT_KIND_UNSUPPORTED -> "TOOL_CODE_FACT_KIND_UNSUPPORTED";
            case INVALID_QUERY -> "TOOL_QUERY_INVALID";
            case SEMANTIC_INDEX_UNAVAILABLE -> "TOOL_DEPENDENCY_UNAVAILABLE";
            case FORBIDDEN -> "TOOL_DEPENDENCY_FORBIDDEN";
            case INVALID_RESPONSE -> "TOOL_DEPENDENCY_INVALID_RESPONSE";
        };
    }

    private static String messageFor(ToolExecutionFailure failure) {
        return switch (failure.kind()) {
            case INVALID_INPUT -> "The tool input is invalid.";
            case INPUT_TOO_LARGE -> "The tool input is too large.";
            default -> failure.safeMessage() + ".";
        };
    }
}
