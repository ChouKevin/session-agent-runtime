package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageJobCancellationPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;

import java.util.Objects;
import java.util.Optional;

public final class JobCancellationService implements MessageJobCancellationPort {

    private final ConversationStore conversationStore;

    public JobCancellationService(ConversationStore conversationStore) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
    }

    @Override
    public Optional<MessageJobView> cancel(String messageJobId) {
        return conversationStore.requestCancellation(new MessageJobId(messageJobId))
                .map(projection -> new MessageJobView(projection.messageJobId().value(), projection.sessionId().value(),
                        projection.status(), projection.retryCount(), projection.modelCallCount(), projection.progress(),
                        projection.failure(), projection.cancellationRequestedAt()));
    }

    @Override
    public int cancelSession(String sessionId) {
        return conversationStore.requestSessionCancellation(new SessionId(sessionId));
    }
}
