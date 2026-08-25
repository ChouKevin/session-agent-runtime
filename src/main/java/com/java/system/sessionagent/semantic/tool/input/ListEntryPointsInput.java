package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

public record ListEntryPointsInput(
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
        @NotBlank String repositoryId,
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
        @NotBlank String revision) {
}
