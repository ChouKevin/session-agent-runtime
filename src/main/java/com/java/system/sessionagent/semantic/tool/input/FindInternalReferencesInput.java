package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record FindInternalReferencesInput(
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
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.METHOD_NAME)
        @NotBlank String methodName,
        @JsonProperty(required = true) @JsonPropertyDescription(SemanticInputDescriptions.PARAMETER_TYPES)
        @NotNull List<@NotBlank String> parameterTypes,
        @JsonPropertyDescription(SemanticInputDescriptions.OFFSET) @Min(0) Integer offset,
        @JsonPropertyDescription(SemanticInputDescriptions.LIMIT) @Min(1) @Max(100) Integer limit) {
}
