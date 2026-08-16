package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;

import java.util.Objects;

public final class ConversationMessageService implements MessageIntakePort {

    private final ConversationStore conversationStore;
    private final ConversationTelemetry telemetry;

    public ConversationMessageService(ConversationStore conversationStore) {
        this(conversationStore, new NoOpConversationTelemetry());
    }

    public ConversationMessageService(ConversationStore conversationStore, ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    @Override
    public MessageReceipt receive(IncomingMessage incomingMessage) {
        try {
            MessageReceipt receipt = conversationStore.receive(Objects.requireNonNull(incomingMessage, "Incoming message must not be null"));
            telemetry.intake("ACCEPTED");
            return receipt;
        } catch (RuntimeException exception) {
            telemetry.intake("REJECTED");
            throw exception;
        }
    }
}
