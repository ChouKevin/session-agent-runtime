package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;
import java.util.Objects;

public record SlackPostRequest(String channelId, String rootThreadTs, String text, SlackPresentation presentation) {

    public SlackPostRequest(String channelId, String rootThreadTs, String text) {
        this(channelId, rootThreadTs, text, SlackPresentation.PLAIN);
    }

    public SlackPostRequest {
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(rootThreadTs, "Slack root thread timestamp must not be blank");
        Assert.hasText(text, "Slack post text must not be blank");
        Objects.requireNonNull(presentation, "Slack presentation must not be null");
        Assert.isTrue(presentation != SlackPresentation.FILE, "Files use the upload protocol");
    }
}
