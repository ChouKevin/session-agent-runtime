package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

public record ConceptTerm(@JsonProperty(required = true) @NotBlank @Size(min = 2, max = 128) String value,
                          @JsonProperty(required = false) ConceptMatchMode matchMode) {
    public ConceptTerm { value = SemanticInputRules.text(value, "Concept term"); matchMode = Objects.requireNonNullElse(matchMode, ConceptMatchMode.TOKEN_PREFIX); }
}
