package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

public record SlackUpdateRequest(String channelId, String messageTs, String text) {
    public SlackUpdateRequest {
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
        Assert.hasText(text, "Slack update text must not be blank");
    }
}
