package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

public record IncomingEvent(
        String eventId,
        String teamId,
        String channelId,
        String messageTs,
        String threadTs,
        String participantId,
        String botId,
        String channelType,
        String text,
        String subtype,
        boolean hidden,
        boolean hasAttachments) {

    public IncomingEvent {
        Assert.hasText(eventId, "Slack event ID must not be blank");
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
    }
}
