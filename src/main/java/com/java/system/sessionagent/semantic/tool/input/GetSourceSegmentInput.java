package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.SemanticLocation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;
import java.util.Objects;

public record GetSourceSegmentInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                    @JsonProperty(required = true) @Valid SemanticLocation location,
                                    @JsonProperty(required = false) @Min(0) @Max(20) Integer contextLines) {
    public GetSourceSegmentInput { Assert.notNull(location, "Source location must not be null"); contextLines = Objects.requireNonNullElse(contextLines, 0); }
}
