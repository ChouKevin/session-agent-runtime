package com.java.system.sessionagent;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModulesTest {

    private static final Map<String, Set<String>> EXPECTED_ALLOWED_DEPENDENCIES = Map.ofEntries(
            Map.entry("tool", Set.of()),
            Map.entry("artifact", Set.of("tool :: domain", "tool :: port")),
            Map.entry("skill", Set.of("tool :: domain", "tool :: port")),
            Map.entry("conversation", Set.of("tool :: domain", "tool :: port", "skill :: domain", "skill :: port")),
            Map.entry("mcp", Set.of("tool :: port")),
            Map.entry("model", Set.of("conversation :: domain", "conversation :: port.out",
                    "tool :: domain", "tool :: port", "skill :: domain")),
            Map.entry("storage", Set.of("conversation :: domain", "conversation :: port.in",
                    "conversation :: port.out", "tool :: domain", "tool :: port", "skill :: domain", "artifact :: port")),
            Map.entry("slack", Set.of("conversation :: domain", "conversation :: port.in", "storage :: adapter.postgres.jdbc", "artifact :: port")),
            Map.entry("web", Set.of("conversation :: domain", "conversation :: port.in", "tool :: domain",
                    "slack :: domain", "slack :: port.out", "skill :: domain")),
            Map.entry("worker", Set.of("conversation :: domain", "conversation :: port.in", "conversation :: port.out")),
            Map.entry("bootstrap", Set.of("tool :: port", "mcp :: configuration", "mcp :: adapter.sdk", "mcp :: adapter.actuator",
                    "artifact :: application", "artifact :: port",
                    "skill :: port", "skill :: configuration", "skill :: adapter.filesystem", "skill :: adapter.tool",
                    "conversation :: application", "conversation :: domain",
                    "conversation :: port.in", "conversation :: port.out", "model :: adapter.springai",
                    "storage :: adapter.postgres", "storage :: adapter.postgres.jdbc", "web",
                    "worker :: application", "worker :: configuration",
                    "slack :: domain", "slack :: application", "slack :: port.out",
                    "slack :: adapter.sdk", "slack :: adapter.postgres", "slack :: configuration")));

    private static final Map<String, Set<String>> EXPECTED_NAMED_INTERFACES = Map.ofEntries(
            Map.entry("tool", Set.of("domain", "port")),
            Map.entry("artifact", Set.of("application", "port")),
            Map.entry("skill", Set.of("domain", "port", "configuration", "adapter.filesystem", "adapter.tool")),
            Map.entry("conversation", Set.of("domain", "application", "port.in", "port.out")),
            Map.entry("model", Set.of("adapter.springai")),
            Map.entry("mcp", Set.of("configuration", "adapter.sdk", "adapter.actuator")),
            Map.entry("storage", Set.of("adapter.postgres", "adapter.postgres.jdbc")),
            Map.entry("web", Set.of()),
            Map.entry("worker", Set.of("application", "configuration")),
            Map.entry("slack", Set.of("domain", "application", "port.out", "adapter.sdk", "adapter.postgres", "configuration")),
            Map.entry("bootstrap", Set.of()));

    @Test
    void containsOnlyTheNewRuntimeModules() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                Set.of("tool", "artifact", "skill", "conversation", "mcp", "model", "storage", "web", "worker", "slack", "bootstrap"),
                modules.stream().map(module -> module.getIdentifier().toString()).collect(Collectors.toSet()));
        modules.verify();
    }

    @Test
    void declaresTheClosedRuntimeDependencyMatrix() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                EXPECTED_ALLOWED_DEPENDENCIES,
                modules.stream().collect(Collectors.toMap(
                        module -> module.getIdentifier().toString(),
                        module -> module.getAllowedDependencies(modules).stream()
                                .map(dependency -> dependency.getTargetModule().getIdentifier().toString()
                                        + (dependency.getTargetNamedInterface().isUnnamed()
                                        ? ""
                                        : " :: " + dependency.getTargetNamedInterface().getName()))
                                .collect(Collectors.toSet()))));
        assertTrue(modules.stream().noneMatch(module -> module.isOpen()));
    }

    @Test
    void declaresTheExactNamedInterfaceMap() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                EXPECTED_NAMED_INTERFACES,
                modules.stream().collect(Collectors.toMap(
                        module -> module.getIdentifier().toString(),
                        module -> module.getNamedInterfaces().stream()
                                .filter(namedInterface -> !namedInterface.isUnnamed())
                                .map(namedInterface -> namedInterface.getName())
                                .collect(Collectors.toSet()))));
    }

}
