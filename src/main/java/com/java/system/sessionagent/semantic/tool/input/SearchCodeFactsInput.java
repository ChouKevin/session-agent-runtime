package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Revision-pinned query. Optional filters are intentionally passed through when absent. */
public record SearchCodeFactsInput(
        @JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from list_repositories or visible prior evidence")
        @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in visible prior evidence; never invent or normalize it")
        @NotBlank @Size(max = 128) String revision,
        @JsonProperty(required = true) @NotBlank @Size(min = 2, max = 128) String query,
        @JsonProperty(required = false) List<CodeFactKind> kinds,
        @JsonProperty(required = false) @JsonPropertyDescription("Omit packagePrefix when unknown rather than guessing") String packagePrefix,
        @JsonProperty(required = false) @Min(0) Integer offset,
        @JsonProperty(required = false) @Min(1) @Max(100) Integer limit) {
}
