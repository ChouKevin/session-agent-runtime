package com.java.system.sessionagent.conversation.domain;

import java.time.Instant;
import java.util.Optional;

public sealed interface SessionMessage
        permits UserMessage, ToolObservation, AssistantMessage, RuntimeMessage {

    SessionId sessionId();

    SessionSequence sequence();

    Optional<MessageJobId> messageJobId();

    Instant createdAt();

    MessageRole role();
}
