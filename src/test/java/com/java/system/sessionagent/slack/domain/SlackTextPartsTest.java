package com.java.system.sessionagent.slack.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTextPartsTest {

    @Test
    void keeps_a_short_multiline_answer_as_one_part() {
        assertThat(SlackTextParts.split("a\nb")).containsExactly("a\nb");
    }

    @Test
    void avoids_blank_parts_for_leading_newlines_and_preserves_surrogate_pairs() {
        String text = "\n" + "x".repeat(3_998) + "🙂";

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).allSatisfy(part -> {
            assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS);
            assertThat(part).isNotBlank();
        });
        assertThat(String.join("", parts)).isEqualTo(text);
    }

    @Test
    void closes_and_reopens_code_fences_without_exceeding_the_limit() {
        String text = "```java\n" + "x".repeat(4_000) + "\n```";

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).hasSize(2).allSatisfy(part ->
                assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS));
        assertThat(parts.getFirst()).endsWith("\n```");
        assertThat(parts.getLast()).startsWith("```\n").endsWith("\n```");
    }

    @Test
    void closes_an_unterminated_fence_only_for_presentation() {
        String text = "```java\n" + "x".repeat(4_000);

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).hasSize(2).allSatisfy(part ->
                assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS));
        assertThat(parts.getFirst()).endsWith("\n```");
        assertThat(parts.getLast()).startsWith("```\n").endsWith("\n```");
        assertThat(parts.getFirst().substring(0, parts.getFirst().length() - "\n```".length())
                + parts.getLast().substring("```\n".length(), parts.getLast().length() - "\n```".length()))
                .isEqualTo(text);
    }
}
