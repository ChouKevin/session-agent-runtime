package com.java.system.sessionagent.semantic.tool.input;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
public record ListEntryPointsInput(@JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from prior evidence") @NotBlank String repositoryId, @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in prior evidence") @NotBlank String revision) { }
