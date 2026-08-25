package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Exact fact lookup from a preceding code-fact search. */
public record GetCodeFactInput(
        @JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from visible prior evidence")
        @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in visible prior evidence")
        @NotBlank @Size(max = 128) String revision,
        @JsonProperty(required = true) @JsonPropertyDescription("Exact factId copied from a prior codebase_search_code_facts result")
        @NotBlank @Size(min = 64, max = 64) String factId) {
}
