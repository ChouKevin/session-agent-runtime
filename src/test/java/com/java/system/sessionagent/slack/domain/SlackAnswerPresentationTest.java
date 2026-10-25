package com.java.system.sessionagent.slack.domain;

import com.java.system.sessionagent.conversation.domain.JobCacheReport;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class SlackAnswerPresentationTest {
    @Test
    void decorated_utf16_boundary_keeps_footer_in_text_and_only_raw_answer_in_file() {
        String footer = "Context cache: 55.6%";
        String separator = "\n\n";
        for (int decoratedLength : new int[] {9_999, 10_000, 10_001}) {
            int rawLength = decoratedLength - separator.length() - footer.length();
            String raw = "😀".repeat(rawLength / 2) + (rawLength % 2 == 0 ? "" : "中");
            SlackAnswerPresentation.Result result = SlackAnswerPresentation.present(raw, footer);
            if (decoratedLength <= 10_000) {
                assertThat(result.presentation()).isEqualTo(SlackPresentation.MARKDOWN);
                assertThat(result.text()).isEqualTo(raw + separator + footer);
                assertThat(result.answerFile()).isEmpty();
            } else {
                assertThat(result.presentation()).isEqualTo(SlackPresentation.PLAIN);
                assertThat(result.text()).isEqualTo(SlackAnswerPresentation.FILE_NOTIFICATION + separator + footer);
                assertThat(result.answerFile()).hasValueSatisfying(bytes ->
                        assertThat(bytes).isEqualTo(raw.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void complete_report_uses_weighted_integer_sums_not_the_rounded_http_ratio() {
        String weighted = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.DONE, 3, 3, 45_000, 25_000));
        assertThat(weighted).contains("55.6%", "25000", "45000").doesNotContain("50.0%");
        // The six-decimal HTTP ratio rounds to 0.555500; Slack must use the original integer sums.
        String independentRounding = SlackCacheReportPresentation.format(
                JobCacheReport.from(JobStatus.DONE, 1, 1, 10_000_000, 5_554_999));
        assertThat(independentRounding).contains("55.5%").doesNotContain("55.6%");
    }

    @Test
    void partial_reports_qualify_known_subset_and_coverage_even_without_a_denominator() {
        String partial = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.DONE, 3, 2, 10_000, 6_000));
        assertThat(partial).contains("資料不完整", "已知部分", "60.0%", "6000", "10000", "2/3");
        String zeroKnownInput = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.DONE, 3, 2, 0, 0));
        assertThat(zeroKnownInput).contains("資料不完整", "已知部分", "2/3").doesNotContain("%");
    }

    @Test
    void distinguishes_no_calls_unknown_usage_and_known_zero_denominator() {
        String notCalled = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.CANCELLED, 0, 0, 0, 0));
        String unavailable = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.DONE, 2, 0, 0, 0));
        String zeroInput = SlackCacheReportPresentation.format(JobCacheReport.from(JobStatus.DONE, 2, 2, 0, 0));
        assertThat(notCalled).contains("未呼叫").doesNotContain("%");
        assertThat(unavailable).contains("未提供").doesNotContain("%");
        assertThat(zeroInput).contains("不適用").doesNotContain("%");
        assertThat(notCalled).isNotEqualTo(unavailable).isNotEqualTo(zeroInput);
        assertThat(unavailable).isNotEqualTo(zeroInput);
    }

    @Test
    void preserves_whole_native_markdown_at_the_inclusive_utf16_boundary() {
        for (int length : new int[] {9_999, 10_000, 10_001}) {
            String prefix = "# heading\n\n```java\nString x = \"中😀\";\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n";
            String text = prefix + "x".repeat(length - prefix.length());
            SlackAnswerPresentation.Result result = SlackAnswerPresentation.present(text, "");
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
        assertThat(SlackAnswerPresentation.present("😀".repeat(4_999) + "a", "").answerFile()).isEmpty();
        assertThat(SlackAnswerPresentation.present("😀".repeat(5_000), "").answerFile()).isEmpty();
        String text = "😀".repeat(5_000) + "中";
        assertThat(SlackAnswerPresentation.present(text, "").answerFile()).hasValueSatisfying(bytes ->
                assertThat(bytes).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void automatic_answer_file_is_not_subject_to_generated_tool_file_limits() {
        String text = "x".repeat(1_048_577);
        assertThat(SlackAnswerPresentation.present(text, "").answerFile()).hasValueSatisfying(bytes ->
                assertThat(bytes).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void standard_notices_default_to_plain_text() {
        assertThat(new SlackPostRequest("C1", "1.0", "Queued").presentation()).isEqualTo(SlackPresentation.PLAIN);
    }
}
