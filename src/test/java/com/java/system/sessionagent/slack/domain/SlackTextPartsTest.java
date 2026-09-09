package com.java.system.sessionagent.slack.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTextPartsTest {

    @Test
    void keeps_a_short_multiline_answer_as_one_part() {
        assertThat(SlackTextParts.split("a\nb")).containsExactly("a\nb");
    }

    @ParameterizedTest
    @MethodSource("whitespaceOnlyDisplayFragments")
    void omits_whitespace_only_display_fragments(String text, List<String> expectedParts) {
        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).containsExactlyElementsOf(expectedParts);
        assertThat(parts).allSatisfy(part -> {
            assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS);
            assertThat(part).isNotBlank();
        });
    }

    private static Stream<Arguments> whitespaceOnlyDisplayFragments() {
        return Stream.of(
                Arguments.of("\n" + "x".repeat(3_998) + "🙂", List.of("x".repeat(3_998) + "🙂")),
                Arguments.of(" ".repeat(4_000) + "x", List.of("x")),
                Arguments.of("x".repeat(3_999) + "\n\n", List.of("x".repeat(3_999) + "\n")),
                Arguments.of("x".repeat(4_000) + "\n\n".repeat(2_000) + "y", List.of("x".repeat(4_000), "y")),
                Arguments.of("\n".repeat(4_000) + "  indented", List.of("  indented")),
                Arguments.of("x".repeat(4_000) + "\n", List.of("x".repeat(4_000))));
    }

    @Test
    void retains_whitespace_only_source_fragments_inside_an_actual_code_fence() {
        String text = "```\n" + " ".repeat(8_000) + "\n```";

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).allSatisfy(part -> {
            assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS);
            assertThat(part).isNotBlank();
        });
        assertThat(parts).anySatisfy(part -> {
            assertThat(part).startsWith("```\n").endsWith("\n```");
            String fencedSource = part.substring("```\n".length(), part.length() - "\n```".length());
            assertThat(fencedSource).isNotEmpty().isBlank();
        });
        assertThat(sourceWithoutPresentationFencesForClosedText(parts)).isEqualTo(text);
    }

    @Test
    void prefers_an_early_usable_newline_without_a_capacity_threshold() {
        String text = "intro\n" + "x".repeat(4_000);

        assertThat(SlackTextParts.split(text))
                .containsExactly("intro\n", "x".repeat(4_000));
    }

    @Test
    void closes_and_reopens_code_fences_without_exceeding_the_limit() {
        String text = "```java\n" + "x".repeat(4_000) + "\n```";

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).hasSizeGreaterThanOrEqualTo(2).allSatisfy(part ->
                assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS));
        assertThat(parts.getFirst()).endsWith("\n```");
        assertThat(parts.getLast()).startsWith("```\n").endsWith("\n```");
    }

    @Test
    void closes_an_unterminated_fence_only_for_presentation() {
        String text = "```java\n" + "x".repeat(4_000);

        List<String> parts = SlackTextParts.split(text);

        assertThat(parts).hasSizeGreaterThanOrEqualTo(2).allSatisfy(part ->
                assertThat(part).hasSizeLessThanOrEqualTo(SlackTextParts.MAXIMUM_UTF16_UNITS));
        assertThat(parts.getFirst()).endsWith("\n```");
        assertThat(parts.getLast()).startsWith("```\n").endsWith("\n```");
        assertThat(sourceWithoutPresentationFences(parts)).isEqualTo(text);
    }

    private static String sourceWithoutPresentationFences(List<String> parts) {
        String firstPart = parts.getFirst().substring(0, parts.getFirst().length() - "\n```".length());
        String remainingParts = parts.subList(1, parts.size()).stream()
                .map(part -> part.substring("```\n".length(), part.length() - "\n```".length()))
                .collect(Collectors.joining());
        return firstPart + remainingParts;
    }

    private static String sourceWithoutPresentationFencesForClosedText(List<String> parts) {
        String firstPart = parts.getFirst().substring(0, parts.getFirst().length() - "\n```".length());
        String middleParts = parts.subList(1, parts.size() - 1).stream()
                .map(part -> part.substring("```\n".length(), part.length() - "\n```".length()))
                .collect(Collectors.joining());
        String finalPart = parts.getLast().substring("```\n".length());
        return firstPart + middleParts + finalPart;
    }
}
