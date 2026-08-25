package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

public record LookupApiRouteInput(
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
        @NotBlank String repositoryId,
        @JsonProperty(required = true)
        @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
        @NotBlank String revision,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact HTTP method from the user question or a prior route candidate")
        @NotBlank String httpMethod,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact API path from the user question or a prior route candidate")
        @NotBlank String path) {
}
