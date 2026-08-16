package com.java.system.sessionagent.tool.application;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class ToolExecutionFailure extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        INPUT_TOO_LARGE,
        UNKNOWN_REPOSITORY,
        REVISION_CHANGED,
        TRANSIENT,
        FORBIDDEN,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final Optional<Duration> retryAfter;

    private ToolExecutionFailure(Kind kind, Optional<Duration> retryAfter, Throwable cause) {
        super(messageFor(kind), cause);
        this.kind = Objects.requireNonNull(kind, "Tool execution failure kind must not be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after must not be null");
        Assert.isTrue(kind == Kind.TRANSIENT || retryAfter.isEmpty(),
                "Only transient failures may carry retry-after");
        retryAfter.ifPresent(duration -> Assert.isTrue(!duration.isNegative(),
                "Retry-after must not be negative"));
    }

    public static ToolExecutionFailure unknownRepository() { return failure(Kind.UNKNOWN_REPOSITORY); }
    public static ToolExecutionFailure invalidInput() { return failure(Kind.INVALID_INPUT); }
    public static ToolExecutionFailure inputTooLarge() { return failure(Kind.INPUT_TOO_LARGE); }
    public static ToolExecutionFailure revisionChanged() { return failure(Kind.REVISION_CHANGED); }
    public static ToolExecutionFailure transientFailure(Optional<Duration> retryAfter) { return new ToolExecutionFailure(Kind.TRANSIENT, retryAfter, null); }
    public static ToolExecutionFailure forbidden() { return failure(Kind.FORBIDDEN); }
    public static ToolExecutionFailure invalidResponse() { return failure(Kind.INVALID_RESPONSE); }

    private static ToolExecutionFailure failure(Kind kind) {
        return new ToolExecutionFailure(kind, Optional.empty(), null);
    }

    public Kind kind() { return kind; }
    public Optional<Duration> retryAfter() { return retryAfter; }

    private static String messageFor(Kind kind) {
        return switch (kind) {
            case INVALID_INPUT -> "Tool input was rejected";
            case INPUT_TOO_LARGE -> "Tool input exceeded the maximum size";
            case UNKNOWN_REPOSITORY -> "Tool repository was not found";
            case REVISION_CHANGED -> "Tool repository revision changed";
            case TRANSIENT -> "Tool dependency is temporarily unavailable";
            case FORBIDDEN -> "Tool dependency access is forbidden";
            case INVALID_RESPONSE -> "Tool dependency returned an invalid response";
        };
    }
}
