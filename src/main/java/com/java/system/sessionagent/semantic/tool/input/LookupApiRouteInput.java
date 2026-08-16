package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LookupApiRouteInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @NotBlank String apiPath,
        @JsonProperty(required = false) String httpMethod) {
    public LookupApiRouteInput { apiPath = SemanticInputRules.text(apiPath, "API path"); httpMethod = SemanticInputRules.optionalText(httpMethod, "HTTP method"); }
}
