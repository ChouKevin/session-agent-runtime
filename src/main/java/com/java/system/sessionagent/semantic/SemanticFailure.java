package com.java.system.sessionagent.semantic;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class SemanticFailure extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        UNKNOWN_REPOSITORY,
        REVISION_CHANGED,
        TRANSIENT,
        FORBIDDEN,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final Optional<Duration> retryAfter;

    private SemanticFailure(Kind kind, Optional<Duration> retryAfter, Throwable cause) {
        super(messageFor(kind), cause);
        this.kind = Objects.requireNonNull(kind, "Semantic failure kind must not be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after must not be null");
        Assert.isTrue(kind == Kind.TRANSIENT || retryAfter.isEmpty(),
                "Only transient failures may carry a retry-after duration");
        retryAfter.ifPresent(duration -> Assert.isTrue(!duration.isNegative(),
                "Retry-after duration must not be negative"));
    }

    public static SemanticFailure unknownRepository() {
        return new SemanticFailure(Kind.UNKNOWN_REPOSITORY, Optional.empty(), null);
    }

    public static SemanticFailure invalidInput() {
        return new SemanticFailure(Kind.INVALID_INPUT, Optional.empty(), null);
    }

    public static SemanticFailure transientFailure(Optional<Duration> retryAfter) {
        return new SemanticFailure(Kind.TRANSIENT, retryAfter, null);
    }

    public static SemanticFailure revisionChanged() {
        return new SemanticFailure(Kind.REVISION_CHANGED, Optional.empty(), null);
    }

    public static SemanticFailure transientFailure(Optional<Duration> retryAfter, Throwable cause) {
        return new SemanticFailure(Kind.TRANSIENT, retryAfter, cause);
    }

    public static SemanticFailure forbidden() {
        return new SemanticFailure(Kind.FORBIDDEN, Optional.empty(), null);
    }

    public static SemanticFailure invalidResponse() {
        return new SemanticFailure(Kind.INVALID_RESPONSE, Optional.empty(), null);
    }

    public Kind kind() {
        return kind;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    private static String messageFor(Kind kind) {
        return switch (kind) {
            case INVALID_INPUT -> "Semantic request input was rejected";
            case UNKNOWN_REPOSITORY -> "Semantic repository was not found";
            case REVISION_CHANGED -> "Semantic repository revision changed";
            case TRANSIENT -> "Semantic service is temporarily unavailable";
            case FORBIDDEN -> "Semantic service access is forbidden";
            case INVALID_RESPONSE -> "Semantic service returned an invalid response";
        };
    }
}
