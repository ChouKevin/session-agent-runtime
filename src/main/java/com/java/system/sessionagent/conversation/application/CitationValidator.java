package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.RepositoryRevisionReader;
import com.java.system.sessionagent.conversation.port.out.RevisionLookup;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class CitationValidator {

    private final ConversationStore conversationStore;
    private final RepositoryRevisionReader revisionReader;

    public CitationValidator(ConversationStore conversationStore, RepositoryRevisionReader revisionReader) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.revisionReader = Objects.requireNonNull(revisionReader, "Repository revision reader must not be null");
    }

    public Validation validate(SessionId sessionId, List<ResultId> citations) {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(citations, "Citations must not be null");
        if (citations.isEmpty() || !unique(citations)) {
            return new Validation.Correctable(CorrectionReason.EMPTY_OR_DUPLICATE);
        }
        Map<String, List<ConversationStore.ResultProjection>> byRepository = new HashMap<>();
        for (ResultId citation : citations) {
            Optional<ConversationStore.ResultProjection> result = conversationStore.readResult(citation);
            if (result.isEmpty()) {
                return new Validation.Correctable(CorrectionReason.RESULT_NOT_FOUND);
            }
            if (!result.get().sessionId().equals(sessionId)) {
                return new Validation.Correctable(CorrectionReason.WRONG_SESSION);
            }
            if (!result.get().citeable()) {
                return new Validation.Correctable(CorrectionReason.NOT_CITEABLE);
            }
            ConversationStore.ResultProjection projection = result.get();
            String repositoryId = projection.repositoryId().orElseThrow();
            byRepository.computeIfAbsent(repositoryId, ignored -> new java.util.ArrayList<>()).add(projection);
        }
        for (Map.Entry<String, List<ConversationStore.ResultProjection>> entry : byRepository.entrySet()) {
            RevisionLookup lookup = revisionReader.read(entry.getKey());
            if (lookup instanceof RevisionLookup.UnknownRepository) {
                return new Validation.Correctable(CorrectionReason.UNKNOWN_REPOSITORY);
            }
            if (lookup instanceof RevisionLookup.TemporaryFailure) {
                return new Validation.Retry(Optional.empty());
            }
            if (lookup instanceof RevisionLookup.Forbidden || lookup instanceof RevisionLookup.InvalidResponse) {
                return new Validation.Terminal();
            }
            String currentRevision = ((RevisionLookup.CurrentRevision) lookup).revision();
            boolean current = entry.getValue().stream()
                    .allMatch(result -> currentRevision.equals(result.revision().orElseThrow()));
            if (!current) {
                return new Validation.Correctable(CorrectionReason.REVISION_CHANGED);
            }
        }
        return new Validation.Accepted(List.copyOf(citations));
    }

    private static boolean unique(List<ResultId> citations) {
        Set<ResultId> values = new HashSet<>(citations);
        return values.size() == citations.size();
    }

    public enum CorrectionReason {
        EMPTY_OR_DUPLICATE,
        RESULT_NOT_FOUND,
        WRONG_SESSION,
        NOT_CITEABLE,
        UNKNOWN_REPOSITORY,
        REVISION_CHANGED
    }

    public sealed interface Validation permits Validation.Accepted, Validation.Correctable, Validation.Retry, Validation.Terminal {
        record Accepted(List<ResultId> citations) implements Validation { }
        record Correctable(CorrectionReason reason) implements Validation {
            public Correctable {
                Objects.requireNonNull(reason, "Citation correction reason must not be null");
            }
        }
        record Retry(Optional<Duration> retryAfter) implements Validation { }
        record Terminal() implements Validation { }
    }
}
