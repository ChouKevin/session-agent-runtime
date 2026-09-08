package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.SessionModelPort;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;

import java.util.Objects;
import java.util.Optional;

public final class SessionModelService implements SessionModelPort {

    private final ConversationStore conversationStore;
    private final ConversationModels conversationModels;

    public SessionModelService(ConversationStore conversationStore, ConversationModels conversationModels) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModels = Objects.requireNonNull(conversationModels, "Conversation models must not be null");
    }

    @Override
    public Optional<SessionModelView> select(SessionId sessionId, String model) {
        ModelSelection selection = conversationModels.findSelection(model)
                .orElseThrow(() -> new IllegalArgumentException("Configured model is not available"));
        return conversationStore.selectModel(sessionId, selection)
                .map(value -> new SessionModelView(sessionId, value.model()));
    }
}
