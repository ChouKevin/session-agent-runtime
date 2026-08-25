package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ResolveSourceSymbolInput(
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
        @JsonPropertyDescription("Exact source symbol text copied from prior Semantic evidence")
        @NotBlank String symbol,
        @JsonPropertyDescription("Optional zero-based line copied from a prior source location; omit when unknown")
        @Min(0) Integer line,
        @JsonPropertyDescription("Optional zero-based character copied from the same source location; omit when unknown")
        @Min(0) Integer character) {
}
