package com.java.system.sessionagent.semantic.tool.input;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
public record DiscoverTypeMembersInput(@JsonProperty(required = true) @JsonPropertyDescription("Exact repositoryId copied from prior evidence") @NotBlank String repositoryId, @JsonProperty(required = true) @JsonPropertyDescription("Exact revision paired with repositoryId in prior evidence") @NotBlank String revision, @JsonProperty(required = true) @NotBlank String packageName, @JsonProperty(required = true) @NotBlank String className, @JsonProperty(required = true) @NotBlank String sourceFile, @JsonProperty(required = true) List<CodeFactKind> kinds, Integer offset, Integer limit) { }
