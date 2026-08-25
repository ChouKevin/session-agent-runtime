package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

/** Exact fact lookup from a preceding code-fact search. */
public record GetCodeFactInput(
        @JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from visible prior evidence")
        @NotBlank String repositoryId,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in visible prior evidence")
        @NotBlank String revision,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact factId copied from a prior codebase_search_code_facts result")
        @NotBlank String factId) {
}
