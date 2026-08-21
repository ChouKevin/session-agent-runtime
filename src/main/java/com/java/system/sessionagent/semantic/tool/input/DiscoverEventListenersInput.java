package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

public record DiscoverEventListenersInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                          @JsonProperty(required = true)
                                          @JsonPropertyDescription("Fully qualified Java event type, for example com.example.order.OrderCancelledEvent")
                                          @NotBlank @Size(max = 1_024)
                                          @Pattern(regexp = SemanticInputRules.QUALIFIED_JAVA_TYPE_SHAPE_PATTERN) String eventType,
                                          @JsonProperty(required = false) @Min(0) Integer offset,
                                          @JsonProperty(required = false) @Min(1) @Max(100) Integer limit) {
    public DiscoverEventListenersInput { eventType = SemanticInputRules.text(eventType, "Event type"); offset = Objects.requireNonNullElse(offset, 0); limit = Objects.requireNonNullElse(limit, 50); }
}
