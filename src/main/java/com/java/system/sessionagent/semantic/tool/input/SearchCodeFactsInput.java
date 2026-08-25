package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Revision-pinned query. Optional filters are intentionally passed through when absent. */
public record SearchCodeFactsInput(
        @JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from list_repositories or visible prior evidence")
        @NotBlank String repositoryId,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in visible prior evidence; never invent or normalize it")
        @NotBlank String revision,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Code or business search terms from the user question; use code-derived names when known")
        @NotBlank String query,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional exact CodeFactKind filters; omit unknown kinds rather than guessing")
        List<CodeFactKind> kinds,
        @JsonProperty(required = false) @JsonPropertyDescription("Omit packagePrefix when unknown rather than guessing") String packagePrefix,
        @JsonProperty(required = false) @JsonPropertyDescription(SemanticInputDescriptions.OFFSET) Integer offset,
        @JsonProperty(required = false) @JsonPropertyDescription(SemanticInputDescriptions.LIMIT) Integer limit) {
}
