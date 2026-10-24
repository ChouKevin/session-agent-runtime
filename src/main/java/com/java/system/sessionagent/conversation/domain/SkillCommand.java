package com.java.system.sessionagent.conversation.domain;

import java.util.Objects;
import java.util.Optional;

public record SkillCommand(String requestedName, String question) {
    public SkillCommand {
        Objects.requireNonNull(requestedName, "Requested name must not be null");
        Objects.requireNonNull(question, "Question must not be null");
    }

    public static Optional<SkillCommand> parse(String text) {
        Objects.requireNonNull(text, "Command text must not be null");
        if (!text.startsWith("/skill:")) {
            return Optional.empty();
        }
        int end = 7;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return Optional.of(new SkillCommand(text.substring(7, end), text.substring(end).stripLeading()));
    }
}
