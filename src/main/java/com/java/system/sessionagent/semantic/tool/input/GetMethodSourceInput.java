package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.MethodTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

public record GetMethodSourceInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                   @JsonProperty(required = true) @Valid MethodTarget target) {
    public GetMethodSourceInput { Assert.notNull(target, "Method target must not be null"); }
}
