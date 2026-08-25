package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

public record DiscoverEventListenersInput(
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
                                          @NotBlank String repositoryId,
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
                                          @NotBlank String revision,
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription("Exact fully qualified Java event type copied from prior Semantic evidence")
                                          @NotBlank String eventType,
                                          @JsonProperty(required = false)
                                          @JsonPropertyDescription(SemanticInputDescriptions.OFFSET) Integer offset,
                                          @JsonProperty(required = false)
                                          @JsonPropertyDescription(SemanticInputDescriptions.LIMIT) Integer limit) {
}
