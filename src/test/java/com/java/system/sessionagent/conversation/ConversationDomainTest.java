package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelCallContext;
import com.java.system.sessionagent.conversation.domain.ModelCallOutcome;
import com.java.system.sessionagent.conversation.domain.ModelCallPhase;
import com.java.system.sessionagent.conversation.domain.ModelCallRecord;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationDomainTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-15T10:15:30Z");
    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final MessageJobId JOB_ID = new MessageJobId("job-1");
    private static final SessionSequence SEQUENCE = new SessionSequence(1);
    private static final String MODEL_CONTEXT = "dGVzdA==";

    @Test
    void preservesExactNonblankIdentifiers() {
        assertThat(new SessionId(" session-1 ").value()).isEqualTo(" session-1 ");
        assertThat(new MessageJobId(" job-1 ").value()).isEqualTo(" job-1 ");
        assertThat(new ResultId(" result-1 ").value()).isEqualTo(" result-1 ");
        assertThat(new SessionSequence(7).value()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankIdentifiers(String blankValue) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionId(blankValue));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessageJobId(blankValue));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultId(blankValue));
    }

    @Test
    void preservesIncomingParticipantAndMessageTextWhileRejectingBlankIdentityFields() {
        IncomingMessage incomingMessage = new IncomingMessage(
                " session-key ", " participant ", " source-message ", "  what is configured?  ");

        assertThat(incomingMessage.participantId()).isEqualTo(" participant ");
        assertThat(incomingMessage.message()).isEqualTo("  what is configured?  ");
        assertThatIllegalArgumentException().isThrownBy(
                () -> new IncomingMessage(" ", "participant", "source-message", "message"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new IncomingMessage("session-key", " ", "source-message", "message"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new IncomingMessage("session-key", "participant", " ", "message"));
    }

    @Test
    void requiresNonblankAssistantMessageText() {
        AssistantMessage assistantMessage = new AssistantMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.of(JOB_ID),
                CREATED_AT,
                MessageRole.ASSISTANT,
                "Answer");

        assertThat(assistantMessage.message()).isEqualTo("Answer");
        assertThatIllegalArgumentException().isThrownBy(() -> new AssistantMessage(
                SESSION_ID, SEQUENCE, Optional.of(JOB_ID), CREATED_AT, MessageRole.ASSISTANT, " "));
    }

    @Test
    void enforcesToolResultScopeRulesAndDefensiveCopyingForHistory() {
        ToolMessage source = toolMessage(Optional.of("payment-service"), Optional.of("revision-1"));
        ToolMessage catalog = toolMessage(Optional.empty(), Optional.empty());
        List<SessionMessage> history = new ArrayList<>(List.of(source));
        ToolSnapshot toolSnapshot = new DirectToolRegistry(List.of()).snapshot();

        ModelCallContext callContext = new ModelCallContext(SESSION_ID, JOB_ID, 1);
        ModelRequest modelRequest = new ModelRequest(history, toolSnapshot, callContext);
        ReplyRequest replyRequest = new ReplyRequest(history, callContext);
        history.clear();

        assertThat(source.repositoryId()).contains("payment-service");
        assertThat(catalog.repositoryId()).isEmpty();
        assertThat(modelRequest.history()).containsExactly(source);
        assertThat(replyRequest.history()).containsExactly(source);
        assertThatThrownBy(() -> modelRequest.history().add(catalog))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelCallContext(SESSION_ID, JOB_ID, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelCallContext(SESSION_ID, JOB_ID, 13));
        assertThatIllegalArgumentException().isThrownBy(
                () -> toolMessage(Optional.of("payment-service"), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> toolMessage(Optional.empty(), Optional.of("revision-1")));
        assertThatIllegalArgumentException().isThrownBy(
                () -> toolMessage(" ", Optional.empty(), Optional.empty()));
    }

    @Test
    void enforcesFencingAndRoleSpecificMessageInvariants() {
        Instant lockedUntil = CREATED_AT.plusSeconds(30);

        assertThat(new MessageWorkClaim(JOB_ID, SESSION_ID, "worker-1", 1, CREATED_AT, lockedUntil).claimNumber())
                .isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MessageWorkClaim(JOB_ID, SESSION_ID, "worker-1", 0, CREATED_AT, lockedUntil));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MessageWorkClaim(JOB_ID, SESSION_ID, "worker-1", 1, CREATED_AT, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> new UserMessage(
                SESSION_ID, SEQUENCE, Optional.empty(), CREATED_AT, MessageRole.TOOL, "participant", "message"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AssistantMessage(
                SESSION_ID, SEQUENCE, Optional.of(JOB_ID), CREATED_AT, MessageRole.FEEDBACK, "answer"));
        assertThatIllegalArgumentException().isThrownBy(() -> new FeedbackMessage(
                SESSION_ID, SEQUENCE, Optional.of(JOB_ID), CREATED_AT, MessageRole.ASSISTANT,
                "INVALID_REPLY", "message", false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void requiresAJobForEveryProcessedMessageRole() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.empty(),
                CREATED_AT,
                MessageRole.TOOL,
                new ResultId("result-1"),
                "call-1",
                MODEL_CONTEXT,
                "list_repositories",
                "v1",
                "{}",
                Optional.empty(),
                Optional.empty(),
                "{\"repositories\":[]}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AssistantMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.empty(),
                CREATED_AT,
                MessageRole.ASSISTANT,
                "Answer"));
        assertThatIllegalArgumentException().isThrownBy(() -> new FeedbackMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.empty(),
                CREATED_AT,
                MessageRole.FEEDBACK,
                "INVALID_REPLY",
                "message",
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void requiresRawRejectedArgumentsForToolFeedbackButNotGeneralFeedback() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FeedbackMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.of(JOB_ID),
                CREATED_AT,
                MessageRole.FEEDBACK,
                "INVALID_TOOL_INPUT",
                "message",
                false,
                Optional.of("call-1"),
                Optional.of("list_repositories"),
                Optional.empty(),
                Optional.of(MODEL_CONTEXT)));

        FeedbackMessage feedbackMessage = new FeedbackMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.of(JOB_ID),
                CREATED_AT,
                MessageRole.FEEDBACK,
                "INVALID_TOOL_INPUT",
                "message",
                false,
                Optional.of("call-1"),
                Optional.of("list_repositories"),
                Optional.of(" {\"repositoryId\":\"payment-service\"} "),
                Optional.of(MODEL_CONTEXT));

        assertThat(feedbackMessage.rejectedArguments()).contains(" {\"repositoryId\":\"payment-service\"} ");
        assertThat(new FeedbackMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.of(JOB_ID),
                CREATED_AT,
                MessageRole.FEEDBACK,
                "INVALID_REPLY",
                "message",
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()).rejectedArguments()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidModelCallRecords")
    void rejectsInvalidModelCallDiagnosticCombinations(
            ModelCallPhase phase,
            ModelCallOutcome outcome,
            Optional<String> rawCompletion,
            Optional<String> rawToolCalls,
            Optional<String> responseError,
            Optional<String> providerError) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelCallRecord(
                UUID.randomUUID(), SESSION_ID, JOB_ID, 1, 1, phase, outcome, "model", "prompt",
                rawCompletion, rawToolCalls, Optional.empty(), responseError, providerError,
                new com.java.system.sessionagent.conversation.domain.ModelUsage(1, 1, 2, true), CREATED_AT, CREATED_AT));
    }

    private static Stream<Arguments> invalidModelCallRecords() {
        return Stream.of(
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.FINAL_REPLY,
                        Optional.of("completion"), Optional.empty(), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.PROVIDER_FAILURE,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.INVALID_RESPONSE,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.TOOL_CALL,
                        Optional.of("completion"), Optional.of("[]"), Optional.empty(), Optional.empty()));
    }

    private static ToolMessage toolMessage(
            Optional<String> repositoryId,
            Optional<String> revision) {
        return toolMessage("list_repositories", repositoryId, revision);
    }

    private static ToolMessage toolMessage(
            String toolName,
            Optional<String> repositoryId,
            Optional<String> revision) {
        return new ToolMessage(
                SESSION_ID,
                SEQUENCE,
                Optional.of(JOB_ID),
                CREATED_AT,
                MessageRole.TOOL,
                new ResultId("result-1"),
                "call-1",
                MODEL_CONTEXT,
                toolName,
                "v1",
                "{}",
                repositoryId,
                revision,
                "{\"repositories\":[]}");
    }
}
