package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

public record SlackPostRequest(String channelId, String rootThreadTs, String text) {

    public SlackPostRequest {
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(rootThreadTs, "Slack root thread timestamp must not be blank");
        Assert.hasText(text, "Slack post text must not be blank");
    }
}
