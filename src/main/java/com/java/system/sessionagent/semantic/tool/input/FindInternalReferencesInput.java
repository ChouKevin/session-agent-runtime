package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.InternalReferenceTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;
import java.util.Objects;

public record FindInternalReferencesInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                          @JsonProperty(required = true) @Valid InternalReferenceTarget target,
                                          @JsonProperty(required = false) @Min(0) Integer offset,
                                          @JsonProperty(required = false) @Min(1) @Max(100) Integer limit) {
    public FindInternalReferencesInput { Assert.notNull(target, "Internal-reference target must not be null"); offset = Objects.requireNonNullElse(offset, 0); limit = Objects.requireNonNullElse(limit, 50); }
}
