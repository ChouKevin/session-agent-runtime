package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.SessionId;

import java.util.Optional;

public interface SessionModelPort {

    Optional<SessionModelView> select(SessionId sessionId, String model);

    record SessionModelView(SessionId sessionId, String model) {
    }
}
