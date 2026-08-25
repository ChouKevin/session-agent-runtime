package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.springframework.util.Assert;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class CitationValidator {

    private final ConversationStore conversationStore;
    public CitationValidator(ConversationStore conversationStore) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
    }

    public Validation validate(SessionId sessionId, List<ResultId> citations) {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(citations, "Citations must not be null");
        if (citations.isEmpty() || !unique(citations)) {
            return new Validation.Correctable(CorrectionReason.EMPTY_OR_DUPLICATE);
        }
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
        NOT_CITEABLE
    }

    public sealed interface Validation permits Validation.Accepted, Validation.Correctable {
        record Accepted(List<ResultId> citations) implements Validation { }
        record Correctable(CorrectionReason reason) implements Validation {
            public Correctable {
                Objects.requireNonNull(reason, "Citation correction reason must not be null");
            }
        }
    }
}
