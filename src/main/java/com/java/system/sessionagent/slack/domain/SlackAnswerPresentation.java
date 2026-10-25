package com.java.system.sessionagent.slack.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Objects;
import org.springframework.util.Assert;

/** Presentation only: never parses, splits, truncates, or calls a model. */
public final class SlackAnswerPresentation {
    public static final String INLINE_NOTIFICATION = "Assistant response (formatted Markdown).";
    public static final String FILE_NOTIFICATION = "The complete response is attached as answer.md.";
    private SlackAnswerPresentation() {}

    public static Result present(String rawAnswer, String footer) {
        Assert.hasText(rawAnswer, "Slack answer must not be blank");
        Objects.requireNonNull(footer, "Slack footer must not be null");
        String suffix = footer.isEmpty() ? "" : "\n\n" + footer;
        // Measure the composed presentation, but never put Runtime decoration in answer.md.
        if (rawAnswer.length() + suffix.length() <= 10_000) {
            return new Result(SlackPresentation.MARKDOWN, rawAnswer + suffix, Optional.empty());
        }
        return new Result(SlackPresentation.PLAIN, FILE_NOTIFICATION + suffix,
                Optional.of(rawAnswer.getBytes(StandardCharsets.UTF_8)));
    }

    public record Result(SlackPresentation presentation, String text, Optional<byte[]> answerFile) {}
}
