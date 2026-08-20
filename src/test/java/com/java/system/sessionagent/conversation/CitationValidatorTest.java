package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.CitationValidator;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.RevisionLookup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    @Test
    void accepts_original_order_after_one_current_revision_read_per_repository() {
        SessionId session = new SessionId("session-1");
        ResultId first = new ResultId("result-1");
        ResultId second = new ResultId("result-2");
        Map<ResultId, ConversationStore.ResultProjection> results = Map.of(
                first, result(first, session, "payment", "rev-1", true),
                second, result(second, session, "order", "rev-2", true));
        Map<String, Integer> reads = new HashMap<>();
        CitationValidator validator = new CitationValidator(new ResultsStore(results), repository -> {
            reads.merge(repository, 1, Integer::sum);
            return new RevisionLookup.CurrentRevision(repository.equals("payment") ? "rev-1" : "rev-2");
        });

        CitationValidator.Validation outcome = validator.validate(session, List.of(second, first));

        assertThat(outcome).isEqualTo(new CitationValidator.Validation.Accepted(List.of(second, first)));
        assertThat(reads).containsExactlyInAnyOrderEntriesOf(Map.of("payment", 1, "order", 1));
    }

    @Test
    void rejects_missing_cross_session_and_catalog_citations_without_revision_reads() {
        SessionId session = new SessionId("session-1");
        ResultId crossSession = new ResultId("cross");
        ResultId catalog = new ResultId("catalog");
        ResultsStore store = new ResultsStore(Map.of(
                crossSession, result(crossSession, new SessionId("session-2"), "payment", "rev-1", true),
                catalog, result(catalog, session, "", "", false)));
        CitationValidator validator = new CitationValidator(store, repository -> { throw new AssertionError("revision read"); });

        assertThat(validator.validate(session, List.of(new ResultId("missing"))))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.RESULT_NOT_FOUND));
        assertThat(validator.validate(session, List.of(crossSession)))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.WRONG_SESSION));
        assertThat(validator.validate(session, List.of(catalog)))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.NOT_CITEABLE));
        assertThat(validator.validate(session, List.of()))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.EMPTY_OR_DUPLICATE));
        assertThat(validator.validate(session, List.of(crossSession, crossSession)))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.EMPTY_OR_DUPLICATE));
    }

    @Test
    void maps_stale_unknown_temporary_and_forbidden_revision_outcomes() {
        SessionId session = new SessionId("session-1");
        ResultId resultId = new ResultId("result-1");
        ResultsStore store = new ResultsStore(Map.of(resultId, result(resultId, session, "payment", "rev-1", true)));

        assertThat(new CitationValidator(store, repository -> new RevisionLookup.CurrentRevision("rev-2")).validate(session, List.of(resultId)))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.REVISION_CHANGED));
        assertThat(new CitationValidator(store, repository -> new RevisionLookup.UnknownRepository()).validate(session, List.of(resultId)))
                .isEqualTo(new CitationValidator.Validation.Correctable(CitationValidator.CorrectionReason.UNKNOWN_REPOSITORY));
        assertThat(new CitationValidator(store, repository -> new RevisionLookup.TemporaryFailure()).validate(session, List.of(resultId)))
                .isInstanceOf(CitationValidator.Validation.Retry.class);
        assertThat(new CitationValidator(store, repository -> new RevisionLookup.Forbidden()).validate(session, List.of(resultId)))
                .isInstanceOf(CitationValidator.Validation.Terminal.class);
    }

    private static ConversationStore.ResultProjection result(ResultId id, SessionId session, String repository, String revision, boolean citeable) {
        return new ConversationStore.ResultProjection(id, session, "source", "v1", "{}",
                citeable ? Optional.of(repository) : Optional.empty(), citeable ? Optional.of(revision) : Optional.empty(), "{}", citeable);
    }

    private static final class ResultsStore implements ConversationStore {
        private final Map<ResultId, ResultProjection> results;
        private ResultsStore(Map<ResultId, ResultProjection> results) { this.results = results; }
        @Override public Optional<ResultProjection> readResult(ResultId resultId) { return Optional.ofNullable(results.get(resultId)); }
        @Override public com.java.system.sessionagent.conversation.domain.MessageReceipt receive(com.java.system.sessionagent.conversation.domain.IncomingMessage message) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.java.system.sessionagent.conversation.domain.MessageWorkClaim> claimNext(String workerId, java.time.Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public boolean extendClaim(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, java.time.Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId) { throw new UnsupportedOperationException(); }
        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId, com.java.system.sessionagent.conversation.domain.MessageJobId messageJobId) { throw new UnsupportedOperationException(); }
        @Override public java.util.OptionalInt reserveModelCall(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, java.time.Instant now) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.ToolMessage appendTool(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, ResultId resultId, String modelCallId, String modelContext, ToolData toolData, java.time.Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.FeedbackMessage appendFeedback(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, String code, String message, boolean terminal, Optional<String> modelCallId, Optional<String> toolName, Optional<String> rejectedArguments, Optional<String> modelContext, java.time.Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.AssistantMessage appendAssistant(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, com.java.system.sessionagent.conversation.domain.AssistantReply reply, java.time.Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public boolean scheduleRetry(com.java.system.sessionagent.conversation.domain.MessageWorkClaim claim, java.time.Duration retryDelay) { throw new UnsupportedOperationException(); }
        @Override public Optional<MessageJobProjection> readJob(com.java.system.sessionagent.conversation.domain.MessageJobId messageJobId) { return Optional.empty(); }
    }
}
