package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public record IncomingMessage(String sessionKey, String participantId, String sourceMessageId, String message) {

    public IncomingMessage {
        Assert.hasText(sessionKey, "Session key must not be blank");
        Assert.hasText(participantId, "Participant ID must not be blank");
        Assert.hasText(sourceMessageId, "Source message ID must not be blank");
        Assert.hasLength(message, "Message must not be empty");
    }
}
