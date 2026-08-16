package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

public record SuggestApiRouteInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @NotBlank String apiPath,
        @JsonProperty(required = false) String httpMethod,
        @JsonProperty(required = false) @Min(1) @Max(20) Integer limit) {
    public SuggestApiRouteInput {
        apiPath = SemanticInputRules.text(apiPath, "API path");
        httpMethod = SemanticInputRules.optionalText(httpMethod, "HTTP method");
        limit = Objects.requireNonNullElse(limit, 10);
    }
}
