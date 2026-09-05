package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import org.springframework.util.Assert;

import java.util.Optional;

public record SlackRootIntake(
        String eventId,
        String teamId,
        String channelId,
        String rootThreadTs,
        String messageTs,
        SlackIntakeClassification classification,
        Optional<IncomingMessage> message) {

    public SlackRootIntake {
        Assert.hasText(eventId, "Slack event ID must not be blank");
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(rootThreadTs, "Slack root timestamp must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
        Assert.notNull(classification, "Slack intake classification must not be null");
        Assert.notNull(message, "Incoming message must not be null");
        Assert.isTrue((classification == SlackIntakeClassification.ACCEPTED) == message.isPresent(),
                "Accepted Slack intake must have exactly one message");
    }
}
