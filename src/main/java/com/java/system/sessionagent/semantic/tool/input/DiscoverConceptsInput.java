package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

public record DiscoverConceptsInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
        @JsonProperty(required = true) @Size(min = 1, max = 4) List<@Valid ConceptTerm> terms,
        @JsonProperty(required = true) @Size(min = 1) List<ConceptKind> kinds,
        @JsonProperty(required = false) String packagePrefix,
        @JsonProperty(required = false) @Min(0) Integer offset,
        @JsonProperty(required = false) @Min(1) @Max(100) Integer limit) {
    public DiscoverConceptsInput {
        terms = SemanticInputRules.distinct(terms, "Concept terms");
        kinds = SemanticInputRules.distinct(kinds, "Concept kinds");
        packagePrefix = SemanticInputRules.optionalText(packagePrefix, "Package prefix");
        offset = Objects.requireNonNullElse(offset, 0);
        limit = Objects.requireNonNullElse(limit, 50);
    }
}
