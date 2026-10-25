package com.java.system.sessionagent.slack.domain;

import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;

/** One durable ordered text or file delivery item, prepared before any parent acknowledgement. */
public record SlackDeliveryPart(int index, String text, SlackPresentation presentation, Optional<SlackFileUpload> file) {
    public SlackDeliveryPart(int index, String text) {
        this(index, text, SlackPresentation.PLAIN, Optional.empty());
    }
    public SlackDeliveryPart {
        Assert.isTrue(index >= 0, "Slack delivery item index must not be negative");
        Assert.hasText(text, "Slack delivery item text must not be blank");
        Objects.requireNonNull(presentation, "Slack presentation must not be null");
        Objects.requireNonNull(file, "Slack file must not be null");
        Assert.isTrue((presentation == SlackPresentation.FILE) == file.isPresent(), "File presentation requires a file");
    }
}
