package com.java.system.sessionagent.slack.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.util.Assert;

/** Presentation only: never parses, splits, truncates, or calls a model. */
public final class SlackAnswerPresentation {
    public static final String INLINE_NOTIFICATION = "Assistant response (formatted Markdown).";
    public static final String FILE_NOTIFICATION = "The complete response is attached as answer.md.";
    private SlackAnswerPresentation() {}

    public static Result present(String text) {
        Assert.hasText(text, "Slack answer must not be blank");
        // A code point occupies at least one UTF-16 unit, so this also guarantees <= 10,000 code points.
        if (text.length() <= 10_000) {
            return new Result(SlackPresentation.MARKDOWN, text, Optional.empty());
        }
        return new Result(SlackPresentation.PLAIN, FILE_NOTIFICATION,
                Optional.of(text.getBytes(StandardCharsets.UTF_8)));
    }

    public record Result(SlackPresentation presentation, String text, Optional<byte[]> answerFile) {}
}
