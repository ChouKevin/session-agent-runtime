package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

public record SlackPermalink(String channelId, String rootThreadTs) {

    public SlackPermalink {
        Assert.hasText(channelId, "Slack permalink channel ID must not be blank");
        Assert.hasText(rootThreadTs, "Slack permalink root thread timestamp must not be blank");
    }
}
