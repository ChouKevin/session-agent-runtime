package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import org.springframework.util.Assert;

public record SlackRootIntake(String teamId, String channelId, String rootThreadTs, String messageTs, IncomingMessage message) {

    public SlackRootIntake {
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(rootThreadTs, "Slack root timestamp must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
        Assert.notNull(message, "Incoming message must not be null");
    }
}
