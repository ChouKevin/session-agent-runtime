package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record IncomingMessage(
        IncomingMessageSource source,
        String sessionKey,
        String participantId,
        String sourceMessageId,
        String message) {

    public IncomingMessage(String sessionKey, String participantId, String sourceMessageId, String message) {
        this(IncomingMessageSource.HTTP, sessionKey, participantId, sourceMessageId, message);
    }

    public IncomingMessage {
        Assert.notNull(source, "Incoming message source must not be null");
        Assert.hasText(sessionKey, "Session key must not be blank");
        Assert.hasText(participantId, "Participant ID must not be blank");
        Assert.hasText(sourceMessageId, "Source message ID must not be blank");
        Assert.hasLength(message, "Message must not be empty");
    }
}
