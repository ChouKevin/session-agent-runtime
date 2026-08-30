package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationMessageServiceTest {

    @Test
    void forwardsTheUnchangedHttpIndependentIncomingMessageToTheConversationStore() {
        AtomicReference<IncomingMessage> receivedMessage = new AtomicReference<>();
        MessageReceipt expectedReceipt = new MessageReceipt(new SessionId("session-1"), new MessageJobId("job-1"));
        ConversationStore conversationStore = new RecordingConversationStore(receivedMessage, () -> expectedReceipt);
        ConversationMessageService service = new ConversationMessageService(conversationStore);
        IncomingMessage incomingMessage = new IncomingMessage(
                " thread-1 ", " participant-1 ", " source-1 ", "  keep this text exactly  ");

        MessageReceipt actualReceipt = service.receive(incomingMessage);

        assertThat(actualReceipt).isEqualTo(expectedReceipt);
        assertThat(Objects.requireNonNull(receivedMessage.get())).isEqualTo(incomingMessage);
    }

    @Test
    void exposesTheConversationConflictWithoutTranslatingItToInfrastructureFailure() {
        AtomicReference<IncomingMessage> receivedMessage = new AtomicReference<>();
        MessageConflictException conflict = new MessageConflictException();
        ConversationStore conversationStore = new RecordingConversationStore(receivedMessage, () -> {
            throw conflict;
        });
        ConversationMessageService service = new ConversationMessageService(conversationStore);
        IncomingMessage incomingMessage = new IncomingMessage("thread-1", "alice", "source-1", "hello");

        assertThatThrownBy(() -> service.receive(incomingMessage))
                .isSameAs(conflict)
                .isExactlyInstanceOf(MessageConflictException.class)
                .hasMessage("Incoming message conflicts with an existing source message");
        assertThat(Objects.requireNonNull(receivedMessage.get())).isEqualTo(incomingMessage);
    }

    @Test
    void emitsClosedIntakeOutcomesAtTheProductionIntakeBoundary() {
        AtomicReference<IncomingMessage> receivedMessage = new AtomicReference<>();
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageReceipt receipt = new MessageReceipt(new SessionId("session-1"), new MessageJobId("job-1"));
        ConversationMessageService accepted = new ConversationMessageService(
                new RecordingConversationStore(receivedMessage, () -> receipt), telemetry);
        ConversationMessageService rejected = new ConversationMessageService(
                new RecordingConversationStore(receivedMessage, () -> {
                    throw new MessageConflictException();
                }), telemetry);

        accepted.receive(new IncomingMessage("thread", "alice", "source-1", "hello"));
        assertThatThrownBy(() -> rejected.receive(new IncomingMessage("thread", "alice", "source-2", "hello")))
                .isInstanceOf(MessageConflictException.class);

        verify(telemetry).intake("ACCEPTED");
        verify(telemetry).intake("REJECTED");
    }

    private static final class RecordingConversationStore implements ConversationStore {

        private final AtomicReference<IncomingMessage> receivedMessage;
        private final Supplier<MessageReceipt> receiveAction;

        private RecordingConversationStore(
                AtomicReference<IncomingMessage> receivedMessage,
                Supplier<MessageReceipt> receiveAction) {
            this.receivedMessage = receivedMessage;
            this.receiveAction = receiveAction;
        }

        @Override
        public MessageReceipt receive(IncomingMessage incomingMessage) {
            receivedMessage.set(incomingMessage);
            return receiveAction.get();
        }

        @Override
        public Optional<MessageWorkClaim> claimNext(String workerId, java.time.Duration leaseDuration) {
            throw new UnsupportedOperationException("Message job claiming is not part of message intake");
        }

        @Override
        public boolean extendClaim(MessageWorkClaim claim, java.time.Duration leaseDuration) {
            throw new UnsupportedOperationException("Message job claiming is not part of message intake");
        }

        @Override
        public List<SessionMessage> loadHistory(SessionId sessionId) {
            throw new UnsupportedOperationException("Message history is not part of message intake");
        }

        @Override
        public List<SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId) {
            throw new UnsupportedOperationException("Message history is not part of message intake");
        }

        @Override
        public OptionalInt reserveModelCall(MessageWorkClaim claim, Instant now) {
            throw new UnsupportedOperationException("Model call reservation is not part of message intake");
        }

        @Override
        public ToolMessage appendTool(
                MessageWorkClaim claim,
                ResultId resultId,
                String modelCallId,
                String modelContext,
                ToolData toolData,
                Instant createdAt) {
            throw new UnsupportedOperationException("Tool persistence is not part of message intake");
        }

        @Override
        public FeedbackMessage appendFeedback(
                MessageWorkClaim claim,
                String code,
                String message,
                boolean terminal,
                Optional<String> modelCallId,
                Optional<String> toolName,
                Optional<String> rejectedArguments,
                Optional<String> modelContext,
                Instant createdAt) {
            throw new UnsupportedOperationException("Feedback persistence is not part of message intake");
        }

        @Override
        public AssistantMessage appendAssistant(MessageWorkClaim claim, String message, Instant createdAt) {
            throw new UnsupportedOperationException("Assistant persistence is not part of message intake");
        }

        @Override
        public boolean scheduleRetry(MessageWorkClaim claim, java.time.Duration retryDelay) {
            throw new UnsupportedOperationException("Retry scheduling is not part of message intake");
        }

        @Override
        public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
            throw new UnsupportedOperationException("Job reads are not part of message intake");
        }

        @Override
        public Optional<ResultProjection> readResult(ResultId resultId) {
            throw new UnsupportedOperationException("Result reads are not part of message intake");
        }
    }
}
