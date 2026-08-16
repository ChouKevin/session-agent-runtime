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
            return new Validation.Correctable();
        }
        Map<String, List<ConversationStore.ResultProjection>> byRepository = new HashMap<>();
        for (ResultId citation : citations) {
            Optional<ConversationStore.ResultProjection> result = conversationStore.readResult(citation);
            if (result.isEmpty() || !result.get().sessionId().equals(sessionId) || !result.get().citeable()) {
                return new Validation.Correctable();
            }
            ConversationStore.ResultProjection projection = result.get();
            String repositoryId = projection.repositoryId().orElseThrow();
            byRepository.computeIfAbsent(repositoryId, ignored -> new java.util.ArrayList<>()).add(projection);
        }
        for (Map.Entry<String, List<ConversationStore.ResultProjection>> entry : byRepository.entrySet()) {
            RevisionLookup lookup = revisionReader.read(entry.getKey());
            if (lookup instanceof RevisionLookup.UnknownRepository) {
                return new Validation.Correctable();
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
                return new Validation.Correctable();
            }
        }
        return new Validation.Accepted(List.copyOf(citations));
    }

    private static boolean unique(List<ResultId> citations) {
        Set<ResultId> values = new HashSet<>(citations);
        return values.size() == citations.size();
    }

    public sealed interface Validation permits Validation.Accepted, Validation.Correctable, Validation.Retry, Validation.Terminal {
        record Accepted(List<ResultId> citations) implements Validation { }
        record Correctable() implements Validation { }
        record Retry(Optional<Duration> retryAfter) implements Validation { }
        record Terminal() implements Validation { }
    }
}
