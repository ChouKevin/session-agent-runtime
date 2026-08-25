package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DiscoverEventListenersInput(
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription(SemanticInputDescriptions.REPOSITORY_ID)
                                          @NotBlank @Size(max = 128) String repositoryId,
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription(SemanticInputDescriptions.REVISION)
                                          @NotBlank @Size(max = 128) String revision,
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription("Exact fully qualified Java event type copied from prior Semantic evidence")
                                          @NotBlank @Size(max = 1_024)
                                          @Pattern(regexp = SemanticInputRules.QUALIFIED_JAVA_TYPE_SHAPE_PATTERN) String eventType,
                                          @JsonProperty(required = false)
                                          @JsonPropertyDescription(SemanticInputDescriptions.OFFSET)
                                          @Min(0) Integer offset,
                                          @JsonProperty(required = false)
                                          @JsonPropertyDescription(SemanticInputDescriptions.LIMIT)
                                          @Min(1) @Max(100) Integer limit) {
    public DiscoverEventListenersInput {
        eventType = SemanticInputRules.text(eventType, "Event type");
    }
}
