package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

public record SlackRootEvent(
        String teamId,
        String channelId,
        String messageTs,
        String threadTs,
        String participantId,
        String botId,
        String channelType,
        String text,
        String subtype) {

    public SlackRootEvent {
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
    }
}
