package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DiscoverTypeMembersInput(
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
        @JsonPropertyDescription("Exact member CodeFactKind values needed for this type; copy known kinds and never guess")
        @NotNull @Size(min = 1) List<CodeFactKind> kinds,
        @JsonPropertyDescription(SemanticInputDescriptions.OFFSET) @Min(0) Integer offset,
        @JsonPropertyDescription(SemanticInputDescriptions.LIMIT) @Min(1) @Max(100) Integer limit) {
}
