package com.java.system.sessionagent.tool.application;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class ToolExecutionFailure extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        INPUT_TOO_LARGE,
        REPOSITORY_NOT_FOUND,
        REVISION_OUTDATED,
        INDEX_NOT_READY,
        INDEX_CONTRACT_MISMATCH,
        CODE_FACT_NOT_FOUND,
        CODE_FACT_KIND_UNSUPPORTED,
        INVALID_QUERY,
        SEMANTIC_INDEX_UNAVAILABLE,
        FORBIDDEN,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final Optional<Duration> retryAfter;
    private final Optional<RevisionOutdatedDetails> revisionOutdated;
    private final String safeMessage;

    private ToolExecutionFailure(Kind kind, Optional<Duration> retryAfter, Throwable cause) {
        this(kind, retryAfter, Optional.empty(), messageFor(kind), cause);
    }

    private ToolExecutionFailure(Kind kind, Optional<Duration> retryAfter, Optional<RevisionOutdatedDetails> revisionOutdated,
                                 Throwable cause) {
        this(kind, retryAfter, revisionOutdated, messageFor(kind), cause);
    }

    private ToolExecutionFailure(Kind kind, Optional<Duration> retryAfter, Optional<RevisionOutdatedDetails> revisionOutdated,
                                 String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.kind = Objects.requireNonNull(kind, "Tool execution failure kind must not be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after must not be null");
        this.revisionOutdated = Objects.requireNonNull(revisionOutdated, "Revision-outdated details must not be null");
        Assert.hasText(safeMessage, "Tool failure message must not be blank");
        this.safeMessage = safeMessage;
        Assert.isTrue(kind == Kind.SEMANTIC_INDEX_UNAVAILABLE || retryAfter.isEmpty(),
                "Only unavailable failures may carry retry-after");
        retryAfter.ifPresent(duration -> Assert.isTrue(!duration.isNegative(),
                "Retry-after must not be negative"));
    }

    public static ToolExecutionFailure repositoryNotFound() { return failure(Kind.REPOSITORY_NOT_FOUND); }
    public static ToolExecutionFailure invalidInput() { return failure(Kind.INVALID_INPUT); }
    public static ToolExecutionFailure inputTooLarge() { return failure(Kind.INPUT_TOO_LARGE); }
    public static ToolExecutionFailure revisionOutdated(String retryGuidance) {
        Assert.hasText(retryGuidance, "Revision-outdated retry guidance must not be blank");
        return new ToolExecutionFailure(Kind.REVISION_OUTDATED, Optional.empty(), Optional.empty(),
                "Tool repository revision is outdated. " + retryGuidance, null);
    }

    /**
     * Temporary compatibility overload for callers that still use the legacy rich failure ABI.
     *
     * @deprecated Task 5 removes revision details from the common runtime failure contract.
     */
    @Deprecated(since = "Task 2", forRemoval = true)
    public static ToolExecutionFailure revisionOutdated(String repositoryId, String requestedRevision, String currentRevision, String retryGuidance) {
        return new ToolExecutionFailure(Kind.REVISION_OUTDATED, Optional.empty(), Optional.of(
                new RevisionOutdatedDetails(repositoryId, requestedRevision, currentRevision, retryGuidance)),
                "Tool repository revision is outdated. " + retryGuidance, null);
    }
    public static ToolExecutionFailure indexNotReady() { return failure(Kind.INDEX_NOT_READY); }
    public static ToolExecutionFailure indexContractMismatch() { return failure(Kind.INDEX_CONTRACT_MISMATCH); }
    public static ToolExecutionFailure codeFactNotFound() { return failure(Kind.CODE_FACT_NOT_FOUND); }
    public static ToolExecutionFailure codeFactKindUnsupported() { return failure(Kind.CODE_FACT_KIND_UNSUPPORTED); }
    public static ToolExecutionFailure invalidQuery() { return failure(Kind.INVALID_QUERY); }
    public static ToolExecutionFailure semanticIndexUnavailable(Optional<Duration> retryAfter) { return new ToolExecutionFailure(Kind.SEMANTIC_INDEX_UNAVAILABLE, retryAfter, null); }
    public static ToolExecutionFailure forbidden() { return failure(Kind.FORBIDDEN); }
    public static ToolExecutionFailure invalidResponse() { return failure(Kind.INVALID_RESPONSE); }

    private static ToolExecutionFailure failure(Kind kind) {
        return new ToolExecutionFailure(kind, Optional.empty(), null);
    }

    public Kind kind() { return kind; }
    public Optional<Duration> retryAfter() { return retryAfter; }
    public Optional<RevisionOutdatedDetails> revisionOutdated() {
        return revisionOutdated;
    }

    public String safeMessage() {
        return safeMessage;
    }

    private static String messageFor(Kind kind) {
        return switch (kind) {
            case INVALID_INPUT -> "Tool input was rejected";
            case INPUT_TOO_LARGE -> "Tool input exceeded the maximum size";
            case REPOSITORY_NOT_FOUND -> "Tool repository was not found";
            case REVISION_OUTDATED -> "Tool repository revision is outdated";
            case INDEX_NOT_READY -> "Tool semantic index is not ready";
            case INDEX_CONTRACT_MISMATCH -> "Tool semantic index contract does not match";
            case CODE_FACT_NOT_FOUND -> "Tool code fact was not found";
            case CODE_FACT_KIND_UNSUPPORTED -> "Tool code fact kind is unsupported";
            case INVALID_QUERY -> "Tool query was rejected";
            case SEMANTIC_INDEX_UNAVAILABLE -> "Tool dependency is temporarily unavailable";
            case FORBIDDEN -> "Tool dependency access is forbidden";
            case INVALID_RESPONSE -> "Tool dependency returned an invalid response";
        };
    }

    public record RevisionOutdatedDetails(String repositoryId, String requestedRevision, String currentRevision, String retryGuidance) {
        public RevisionOutdatedDetails {
            Assert.hasText(repositoryId, "Repository ID must not be blank");
            Assert.hasText(requestedRevision, "Requested revision must not be blank");
            Assert.hasText(currentRevision, "Current revision must not be blank");
            Assert.hasText(retryGuidance, "Revision-outdated retry guidance must not be blank");
        }
    }
}
