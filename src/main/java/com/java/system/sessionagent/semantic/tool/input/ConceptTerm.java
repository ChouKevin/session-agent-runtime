package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

public record ConceptTerm(@JsonProperty(required = true)
                          @JsonPropertyDescription("2–128 character source identifier token")
                          @NotBlank @Size(min = 2, max = 128) String value,
                          @JsonProperty(required = false)
                          @JsonPropertyDescription("TOKEN_EXACT matches exact tokens; TOKEN_PREFIX matches token prefixes")
                          ConceptMatchMode matchMode) {
    public ConceptTerm { value = SemanticInputRules.text(value, "Concept term"); matchMode = Objects.requireNonNullElse(matchMode, ConceptMatchMode.TOKEN_PREFIX); }
}
