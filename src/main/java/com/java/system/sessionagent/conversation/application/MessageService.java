package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;

import java.util.Objects;

public final class MessageService implements MessageIntakePort {

    private final ConversationStore conversationStore;
    private final ConversationModels conversationModels;
    private final ConversationTelemetry telemetry;

    public MessageService(
            ConversationStore conversationStore,
            ConversationModels conversationModels,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModels = Objects.requireNonNull(conversationModels, "Conversation models must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    @Override
    public MessageReceipt receive(IncomingMessage incomingMessage) {
        try {
            IncomingMessage requiredIncomingMessage = Objects.requireNonNull(incomingMessage, "Incoming message must not be null");
            MessageReceipt receipt = receiveWithConfiguredModel(requiredIncomingMessage);
            telemetry.intake("ACCEPTED");
            return receipt;
        } catch (RuntimeException exception) {
            telemetry.intake("REJECTED");
            throw exception;
        }
    }

    private MessageReceipt receiveWithConfiguredModel(IncomingMessage incomingMessage) {
        return conversationStore.receive(incomingMessage, conversationModels.defaultSelection(), conversationModels);
    }
}
