package com.java.system.sessionagent.semantic;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class SemanticFailure extends RuntimeException {

    public enum Kind {
        INVALID_ARGUMENT,
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

    private SemanticFailure(Kind kind, Optional<Duration> retryAfter, Throwable cause) {
        this(kind, retryAfter, Optional.empty(), cause);
    }

    private SemanticFailure(Kind kind, Optional<Duration> retryAfter, Optional<RevisionOutdatedDetails> revisionOutdated,
                            Throwable cause) {
        super(messageFor(kind), cause);
        this.kind = Objects.requireNonNull(kind, "Semantic failure kind must not be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after must not be null");
        this.revisionOutdated = Objects.requireNonNull(revisionOutdated, "Revision-outdated details must not be null");
        Assert.isTrue(kind == Kind.SEMANTIC_INDEX_UNAVAILABLE || retryAfter.isEmpty(),
                "Only unavailable failures may carry a retry-after duration");
        retryAfter.ifPresent(duration -> Assert.isTrue(!duration.isNegative(),
                "Retry-after duration must not be negative"));
    }

    public static SemanticFailure repositoryNotFound() {
        return new SemanticFailure(Kind.REPOSITORY_NOT_FOUND, Optional.empty(), null);
    }

    public static SemanticFailure invalidArgument() {
        return new SemanticFailure(Kind.INVALID_ARGUMENT, Optional.empty(), null);
    }

    public static SemanticFailure indexNotReady() {
        return new SemanticFailure(Kind.INDEX_NOT_READY, Optional.empty(), null);
    }

    public static SemanticFailure indexContractMismatch() {
        return new SemanticFailure(Kind.INDEX_CONTRACT_MISMATCH, Optional.empty(), null);
    }

    public static SemanticFailure codeFactNotFound() {
        return new SemanticFailure(Kind.CODE_FACT_NOT_FOUND, Optional.empty(), null);
    }

    public static SemanticFailure codeFactKindUnsupported() {
        return new SemanticFailure(Kind.CODE_FACT_KIND_UNSUPPORTED, Optional.empty(), null);
    }

    public static SemanticFailure invalidQuery() {
        return new SemanticFailure(Kind.INVALID_QUERY, Optional.empty(), null);
    }

    public static SemanticFailure revisionOutdated(String repositoryId, String requestedRevision, String currentRevision, String retryGuidance) {
        return new SemanticFailure(Kind.REVISION_OUTDATED, Optional.empty(), Optional.of(
                new RevisionOutdatedDetails(repositoryId, requestedRevision, currentRevision, retryGuidance)), null);
    }

    public static SemanticFailure semanticIndexUnavailable(Optional<Duration> retryAfter, Throwable cause) {
        return new SemanticFailure(Kind.SEMANTIC_INDEX_UNAVAILABLE, retryAfter, cause);
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
    public Optional<RevisionOutdatedDetails> revisionOutdated() {
        return revisionOutdated;
    }

    private static String messageFor(Kind kind) {
        return switch (kind) {
            case INVALID_ARGUMENT -> "Semantic request input was rejected";
            case REPOSITORY_NOT_FOUND -> "Semantic repository was not found";
            case REVISION_OUTDATED -> "Semantic repository revision is outdated";
            case INDEX_NOT_READY -> "Semantic index is not ready";
            case INDEX_CONTRACT_MISMATCH -> "Semantic index contract does not match";
            case CODE_FACT_NOT_FOUND -> "Semantic code fact was not found";
            case CODE_FACT_KIND_UNSUPPORTED -> "Semantic code fact kind is unsupported";
            case INVALID_QUERY -> "Semantic query was rejected";
            case SEMANTIC_INDEX_UNAVAILABLE -> "Semantic service is temporarily unavailable";
            case FORBIDDEN -> "Semantic service access is forbidden";
            case INVALID_RESPONSE -> "Semantic service returned an invalid response";
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
