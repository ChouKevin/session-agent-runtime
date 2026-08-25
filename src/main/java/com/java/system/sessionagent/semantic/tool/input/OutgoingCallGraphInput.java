package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record OutgoingCallGraphInput(
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
        @JsonPropertyDescription("Call-graph depth from 1 to 2; omit to use the provider default")
        @Min(1) @Max(2) Integer depth,
        @JsonPropertyDescription("Optional node budget for depth-two expansion; omit unless the query needs a known bound")
        @Min(0) Integer depthTwoNodeBudget) {
}
