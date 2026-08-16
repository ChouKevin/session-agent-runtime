package com.java.system.sessionagent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaFactoryTest {

    @Test
    void generates_a_closed_draft_2020_12_schema_that_respects_jackson_and_jakarta_contracts() {
        String schema = new ToolSchemaFactory().schemaFor(SampleInput.class);

        assertTrue(schema.contains("\"$schema\":\"https://json-schema.org/draft/2020-12/schema\""));
        assertTrue(schema.contains("\"additionalProperties\":false"));
        assertTrue(schema.contains("\"repositoryId\""));
        assertTrue(schema.contains("\"required\":[\"repositoryId\"]"));
        assertTrue(schema.contains("\"minimum\":1"));
        assertTrue(schema.contains("\"maximum\":20"));
        assertFalse(schema.contains("\"default\":10"));
    }

    record SampleInput(
            @JsonProperty(required = true) @NotBlank String repositoryId,
            @JsonProperty(required = false) @Min(1) @Max(20) Integer limit) {

        SampleInput {
            limit = java.util.Objects.requireNonNullElse(limit, 10);
        }
    }
}
