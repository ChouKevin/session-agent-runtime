package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GetSourceSegmentInput(
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
        @NotBlank String repositoryId,
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
        @NotBlank String revision,
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.PACKAGE_NAME)
        @NotBlank String packageName,
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.CLASS_NAME)
        @NotBlank String className,
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.SOURCE_FILE)
        @NotBlank String sourceFile,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact zero-based startLine copied from a prior Semantic source range")
        @NotNull Integer startLine,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact zero-based startCharacter copied from the same prior source range")
        @NotNull Integer startCharacter,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact zero-based endLine copied from the same prior source range")
        @NotNull Integer endLine,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact zero-based endCharacter copied from the same prior source range")
        @NotNull Integer endCharacter) {
}
