package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.SessionMessage;

import java.util.List;
import java.util.Optional;

public interface ConversationQueryPort {

    Optional<MessageJobView> findJob(String messageJobId);

    Optional<List<SessionMessage>> messages(String sessionId);

    Optional<SessionDetailView> session(String sessionId);

    default Optional<List<SessionMessage>> messages(String sessionId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit <= 0) {
            throw new IllegalArgumentException("History pagination must be nonnegative with a positive limit");
        }
        return messages(sessionId).map(history -> history.stream()
                .filter(message -> message.sequence().value() > afterSequence)
                .limit(limit)
                .toList());
    }

}
