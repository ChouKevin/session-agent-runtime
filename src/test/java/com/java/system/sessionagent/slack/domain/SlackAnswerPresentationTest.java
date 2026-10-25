package com.java.system.sessionagent.slack.domain;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class SlackAnswerPresentationTest {
    @Test
    void preserves_whole_native_markdown_at_the_inclusive_utf16_boundary() {
        for (int length : new int[] {9_999, 10_000, 10_001}) {
            String prefix = "# heading\n\n```java\nString x = \"中😀\";\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n";
            String text = prefix + "x".repeat(length - prefix.length());
            SlackAnswerPresentation.Result result = SlackAnswerPresentation.present(text);
            if (length <= 10_000) {
                assertThat(result.presentation()).isEqualTo(SlackPresentation.MARKDOWN);
                assertThat(result.text()).isEqualTo(text);
                assertThat(result.answerFile()).isEmpty();
            } else {
                assertThat(result.presentation()).isEqualTo(SlackPresentation.PLAIN);
                assertThat(result.answerFile()).hasValueSatisfying(bytes ->
                        assertThat(bytes).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void supplementary_unicode_counts_utf16_units_without_splitting_or_summarizing() {
        assertThat(SlackAnswerPresentation.present("😀".repeat(4_999) + "a").answerFile()).isEmpty();
        assertThat(SlackAnswerPresentation.present("😀".repeat(5_000)).answerFile()).isEmpty();
        String text = "😀".repeat(5_000) + "中";
        assertThat(SlackAnswerPresentation.present(text).answerFile()).hasValueSatisfying(bytes ->
                assertThat(bytes).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void automatic_answer_file_is_not_subject_to_generated_tool_file_limits() {
        String text = "x".repeat(1_048_577);
        assertThat(SlackAnswerPresentation.present(text).answerFile()).hasValueSatisfying(bytes ->
                assertThat(bytes).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void standard_notices_default_to_plain_text() {
        assertThat(new SlackPostRequest("C1", "1.0", "Queued").presentation()).isEqualTo(SlackPresentation.PLAIN);
    }
}
