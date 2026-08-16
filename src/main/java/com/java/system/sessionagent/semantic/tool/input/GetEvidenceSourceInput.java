package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.EvidenceIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

public record GetEvidenceSourceInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                     @JsonProperty(required = true) @Valid EvidenceIdentity identity) {
    public GetEvidenceSourceInput { Assert.notNull(identity, "Evidence identity must not be null"); }
}
