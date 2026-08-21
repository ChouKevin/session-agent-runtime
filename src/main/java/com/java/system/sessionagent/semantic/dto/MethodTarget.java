package com.java.system.sessionagent.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.java.system.sessionagent.semantic.tool.input.SemanticInputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

import java.util.List;

/** Exact provider method-target payload, deliberately separate from repository-scoped tool inputs. */
public record MethodTarget(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact declaring source type copied from a prior Semantic result")
        @NotNull @Valid SourceType sourceType,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact method name copied from a prior Semantic method target")
        @NotNull @NotBlank String methodName,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Ordered fully-qualified parameter type names; use an empty array for a no-argument method")
        @NotNull List<@NotBlank String> parameterTypes) {

    public MethodTarget {
        Assert.notNull(sourceType, "Method source type must not be null");
        methodName = SemanticInputRules.text(methodName, "Method name");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public record SourceType(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Exact Java type identity copied from a prior Semantic result")
            @NotNull @Valid JavaType javaType,
            @JsonProperty(required = true)
            @JsonPropertyDescription("Normalized repository-relative source file path copied from a prior Semantic result")
            @NotNull @NotBlank @Size(max = 1_024) String sourceFile) {
        public SourceType {
            Assert.notNull(javaType, "Java type must not be null");
            sourceFile = SemanticInputRules.path(sourceFile);
        }
    }

    public record JavaType(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Exact Java package name copied from a prior Semantic result")
            @NotNull @NotBlank String packageName,
            @JsonProperty(required = true)
            @JsonPropertyDescription("Exact Java class name copied from a prior Semantic result")
            @NotNull @NotBlank String className) {
        public JavaType {
            packageName = SemanticInputRules.text(packageName, "Java package name");
            className = SemanticInputRules.text(className, "Java class name");
        }
    }
}
