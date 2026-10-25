package com.java.system.sessionagent.conversation.domain;

import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;

public record IntentResult(FollowUpIntent intent, Optional<String> clarification) {
    public IntentResult {
        Objects.requireNonNull(intent, "Follow-up intent must not be null");
        Objects.requireNonNull(clarification, "Clarification must not be null");
        Assert.isTrue((intent == FollowUpIntent.CLARIFY) == clarification.isPresent(),
                "Only CLARIFY requires clarification text");
        clarification.ifPresent(text -> Assert.hasText(text, "Clarification must not be blank"));
    }
}
