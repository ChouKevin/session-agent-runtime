package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.SessionMessage;

import java.util.List;
import java.util.Optional;

public interface ConversationQueryPort {

    Optional<MessageJobView> findJob(String messageJobId);

    Optional<List<SessionMessage>> messages(String sessionId);

}
