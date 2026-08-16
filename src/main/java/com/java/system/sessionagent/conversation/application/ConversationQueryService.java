package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.port.in.ConversationResultView;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class ConversationQueryService implements ConversationQueryPort {

    private final ConversationStore conversationStore;

    public ConversationQueryService(ConversationStore conversationStore) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
    }

    @Override
    public Optional<MessageJobView> findJob(String messageJobId) {
        return conversationStore.readJob(new MessageJobId(messageJobId)).map(projection -> new MessageJobView(
                projection.messageJobId().value(), projection.sessionId().value(), projection.status(), projection.retryCount(),
                projection.modelCallCount(), projection.replySequence().map(sequence -> OptionalLong.of(sequence.value()))
                        .orElseGet(OptionalLong::empty)));
    }

    @Override
    public Optional<List<SessionMessage>> messages(String sessionId) {
        List<SessionMessage> messages = conversationStore.loadHistory(new SessionId(sessionId));
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.stream()
                .sorted(Comparator.comparingLong(message -> message.sequence().value())).toList());
    }

    @Override
    public Optional<ConversationResultView> findResult(String resultId) {
        return conversationStore.readResult(new ResultId(resultId)).map(projection -> new ConversationResultView(
                projection.resultId().value(), projection.sessionId().value(), projection.toolName(), projection.toolVersion(),
                projection.canonicalArguments(), projection.repositoryId(), projection.revision(), projection.resultJson(), projection.citeable()));
    }
}
