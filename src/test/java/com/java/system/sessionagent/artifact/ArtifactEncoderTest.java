package com.java.system.sessionagent.artifact;

import com.java.system.sessionagent.artifact.application.ArtifactEncoder;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactEncoderTest {
    @Test
    void preserves_exact_markdown_unicode_tables_code_and_line_endings() {
        String source = "  # 中文😀\r\n| a | b |\n|---|---|\n```java\n  <>&\n```\n";
        ArtifactEncoder.Encoded encoded = ArtifactEncoder.markdown(Map.of("title", "報告😀", "content", source));
        assertThat(encoded.content()).isEqualTo(source.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.extension()).isEqualTo("md");
    }

    @Test
    void exports_quoted_multiline_csv_with_bom_crlf_and_disclosed_formula_transformation() {
        ArtifactEncoder.Encoded encoded = ArtifactEncoder.csv(Map.of("title", "表格", "columns", List.of("中文😀", "quoted,header"),
                "rows", List.of(List.of("a\"b\nc", "  =1"), List.of("+2", "-3"), List.of("@x", "\tplain"),
                        List.of("\rplain", "\nplain"), List.of("\u3000=4", "ordinary - text"))));
        assertThat(new String(encoded.content(), StandardCharsets.UTF_8)).isEqualTo(
                "\ufeff\"中文😀\",\"quoted,header\"\r\n\"a\"\"b\nc\",\"'  =1\"\r\n\"'+2\",\"'-3\"\r\n\"'@x\",\"'\tplain\"\r\n\"'\rplain\",\"'\nplain\"\r\n\"'\u3000=4\",\"ordinary - text\"\r\n");
        assertThat(encoded.disclosures().get("spreadsheetSafeTransformation")).isEqualTo(ArtifactEncoder.CSV_DISCLOSURE);
    }

    @Test
    void accepts_inclusive_byte_title_row_and_column_boundaries() {
        assertThat(ArtifactEncoder.markdown(Map.of("title", "😀".repeat(120), "content", "a".repeat(1_048_576))).content()).hasSize(1_048_576);
        assertThat(ArtifactEncoder.csv(Map.of("title", "", "columns", Collections.nCopies(100, ""), "rows", List.of())).content()).isNotEmpty();
        assertThat(ArtifactEncoder.csv(Map.of("title", "", "columns", List.of(""), "rows", Collections.nCopies(10_000, List.of("")))).content()).isNotEmpty();
    }

    @Test
    void rejects_nonrectangular_typed_unknown_or_oversized_input_before_storage() {
        for (Map<String, Object> input : List.of(
                Map.<String, Object>of("title", "x", "columns", List.of("a"), "rows", List.of(List.of())),
                Map.<String, Object>of("title", "x", "columns", List.of(1), "rows", List.of()),
                Map.<String, Object>of("title", "x", "columns", List.of("a"), "rows", List.of(List.of(1))),
                Map.<String, Object>of("title", "x", "columns", List.of(), "rows", List.of()),
                Map.<String, Object>of("title", "x", "columns", Collections.nCopies(101, "a"), "rows", List.of()),
                Map.<String, Object>of("title", "x", "columns", List.of("a"), "rows", Collections.nCopies(10_001, List.of("a"))),
                Map.<String, Object>of("title", "x", "columns", List.of("a"), "rows", List.of(List.of("\"".repeat(524_288)))),
                Map.<String, Object>of("title", "x", "columns", List.of("a"), "rows", List.of(), "extra", true))) {
            assertThatThrownBy(() -> ArtifactEncoder.csv(input)).isInstanceOf(IllegalArgumentException.class);
        }
        for (Map<String, Object> input : List.of(
                Map.<String, Object>of("title", "😀".repeat(121), "content", "ok"),
                Map.<String, Object>of("title", "x", "content", "a".repeat(1_048_577)),
                Map.<String, Object>of("title", "x", "content", "中".repeat(349_526)),
                Map.<String, Object>of("title", "x", "content", "\ud800"),
                Map.<String, Object>of("title", "x", "content", 1))) {
            assertThatThrownBy(() -> ArtifactEncoder.markdown(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
