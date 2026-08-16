package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ListEntryPointsInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = false) EntryPointType type) {
}
