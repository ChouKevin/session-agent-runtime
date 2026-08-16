package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.MethodTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

import java.util.Objects;

public record OutgoingCallGraphInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @Valid MethodTarget target,
        @JsonProperty(required = false) @Min(1) @Max(2) Integer depth) {
    public OutgoingCallGraphInput { Assert.notNull(target, "Call graph target must not be null"); depth = Objects.requireNonNullElse(depth, 2); }
}
