package com.java.system.sessionagent.semantic.dto;

import com.java.system.sessionagent.semantic.tool.input.SemanticInputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

import java.util.List;

/** Exact provider method-target payload, deliberately separate from repository-scoped tool inputs. */
public record MethodTarget(@Valid SourceType sourceType,
                           @NotBlank String methodName,
                           List<@NotBlank String> parameterTypes) {

    public MethodTarget {
        Assert.notNull(sourceType, "Method source type must not be null");
        methodName = SemanticInputRules.text(methodName, "Method name");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public record SourceType(@Valid JavaType javaType, @NotBlank @Size(max = 1_024) String sourceFile) {
        public SourceType {
            Assert.notNull(javaType, "Java type must not be null");
            sourceFile = SemanticInputRules.path(sourceFile);
        }
    }

    public record JavaType(@NotBlank String packageName, @NotBlank String className) {
        public JavaType {
            packageName = SemanticInputRules.text(packageName, "Java package name");
            className = SemanticInputRules.text(className, "Java class name");
        }
    }
}
