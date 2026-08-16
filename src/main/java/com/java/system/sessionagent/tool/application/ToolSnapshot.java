package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ToolSnapshot {

    private final List<ToolRegistration<?>> registrations;
    private final List<ToolDefinition> definitions;

    ToolSnapshot(List<ToolRegistration<?>> registrations) {
        this.registrations = List.copyOf(registrations);
        this.definitions = this.registrations.stream().map(ToolRegistration::definition).toList();
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }

    Optional<ToolRegistration<?>> registration(ToolName name) {
        Objects.requireNonNull(name, "Tool name must not be null");
        return registrations.stream().filter(registration -> registration.definition().name().equals(name)).findFirst();
    }
}
