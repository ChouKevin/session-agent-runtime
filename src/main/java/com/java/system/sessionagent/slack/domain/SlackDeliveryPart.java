package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

/** A fixed, ordered presentation fragment of one durable Slack delivery. */
public record SlackDeliveryPart(int index, String text) {

    public SlackDeliveryPart {
        Assert.isTrue(index >= 0, "Slack delivery part index must not be negative");
        Assert.hasText(text, "Slack delivery part text must not be blank");
    }
}
