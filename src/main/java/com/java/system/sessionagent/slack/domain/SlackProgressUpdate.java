package com.java.system.sessionagent.slack.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;

public record SlackProgressUpdate(long revision, SlackUpdateRequest request, boolean terminal, Instant observedAt,
        String sourceMessageTs, Optional<String> questionPermalink) {
    public SlackProgressUpdate {
        Assert.isTrue(revision > 0, "Progress revision must be positive");
        Objects.requireNonNull(request, "Update request must not be null");
        Objects.requireNonNull(observedAt, "Observation time must not be null");
        Assert.hasText(sourceMessageTs, "Source timestamp must not be blank");
        Objects.requireNonNull(questionPermalink, "Question permalink must not be null");
    }
}
