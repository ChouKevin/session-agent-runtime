package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.SemanticIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

public record ResolveConceptInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                  @JsonProperty(required = true) @Valid SemanticIdentity identity) {
    public ResolveConceptInput { Assert.notNull(identity, "Concept identity must not be null"); }
}
