package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

public record SuggestApiRouteInput(
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
        @NotBlank String repositoryId,
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
        @NotBlank String revision,
        @JsonProperty(required = true)
        @JsonPropertyDescription("HTTP method from the user question; use the exact method when known")
        @NotBlank String httpMethod,
        @JsonProperty(required = true)
        @JsonPropertyDescription("API path or path fragment from the user question")
        @NotBlank String path) {
}
