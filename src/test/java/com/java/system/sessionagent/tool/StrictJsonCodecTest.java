package com.java.system.sessionagent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictJsonCodecTest {

    private final StrictJsonCodec codec = new StrictJsonCodec();

    @Test
    void decodes_valid_input_applies_the_constructor_default_and_canonicalizes_properties() {
        SampleInput input = codec.decode("{\"repositoryId\":\"repo-a\"}", SampleInput.class);

        assertEquals(new SampleInput("repo-a", 10), input);
        assertEquals("{\"limit\":10,\"repositoryId\":\"repo-a\"}", codec.canonicalize(input));
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejects_json_contract_violations(String rawArguments) {
        assertThrows(JsonContractException.class, () -> codec.decode(rawArguments, SampleInput.class));
    }

    @Test
    void rejects_a_document_over_the_supplied_utf8_limit() {
        String rawArguments = "{\"repositoryId\":\"" + "界".repeat(3) + "\"}";

        assertThrows(JsonContractException.class, () -> codec.decodeBounded(rawArguments, SampleInput.class, 20));
    }

    @Test
    void decodes_a_document_larger_than_the_registry_limit_when_unbounded() {
        String rawArguments = validInputWithUtf8Length(65_537);

        SampleInput input = codec.decode(rawArguments, SampleInput.class);

        assertEquals(65_518, input.repositoryId().length());
    }

    private static Stream<String> invalidDocuments() {
        return Stream.of(
                "{\"repositoryId\":\"repo-a\",\"unexpected\":true}",
                "{\"repositoryId\":\"repo-a\",\"repositoryId\":\"repo-b\"}",
                "{\"repositoryId\":null}",
                "{\"repositoryId\":\"repo-a\",\"limit\":\"2\"}",
                "{\"repositoryId\":\"repo-a\"} {}",
                "{\"repositoryId\":\"repo-a\",\"limit\":0}");
    }

    private static String validInputWithUtf8Length(int utf8ByteLength) {
        String prefix = "{\"repositoryId\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(utf8ByteLength - prefix.length() - suffix.length()) + suffix;
    }

    record SampleInput(
            @JsonProperty(required = true) @NotBlank String repositoryId,
            @JsonProperty(required = false) @Min(1) @Max(20) Integer limit) {

        SampleInput {
            limit = java.util.Objects.requireNonNullElse(limit, 10);
        }
    }
}
