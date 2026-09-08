package com.java.system.sessionagent;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModulesTest {

    private static final Map<String, Set<String>> EXPECTED_ALLOWED_DEPENDENCIES = Map.of(
            "tool", Set.of(),
            "conversation", Set.of("tool :: domain", "tool :: port"),
            "mcp", Set.of("tool :: port"),
            "model", Set.of("conversation :: domain", "conversation :: port.out",
                    "tool :: domain", "tool :: port"),
            "storage", Set.of("conversation :: domain", "conversation :: port.in",
                    "conversation :: port.out", "tool :: domain"),
            "slack", Set.of("conversation :: domain", "conversation :: port.in"),
            "web", Set.of("conversation :: domain", "conversation :: port.in", "tool :: domain",
                    "slack :: domain", "slack :: port.out"),
            "worker", Set.of("conversation :: domain", "conversation :: port.in", "conversation :: port.out"),
            "bootstrap", Set.of("tool :: port", "mcp :: configuration", "mcp :: adapter.sdk", "mcp :: adapter.actuator",
                    "conversation :: application", "conversation :: domain",
                    "conversation :: port.in", "conversation :: port.out", "model :: adapter.springai",
                    "storage :: adapter.postgres", "web", "worker :: application", "worker :: configuration",
                    "slack :: domain", "slack :: application", "slack :: port.out",
                    "slack :: adapter.sdk", "slack :: adapter.postgres", "slack :: configuration"));

    private static final Map<String, Set<String>> EXPECTED_NAMED_INTERFACES = Map.of(
            "tool", Set.of("domain", "port"),
            "conversation", Set.of("domain", "application", "port.in", "port.out"),
            "model", Set.of("adapter.springai"),
            "mcp", Set.of("configuration", "adapter.sdk", "adapter.actuator"),
            "storage", Set.of("adapter.postgres"),
            "web", Set.of(),
            "worker", Set.of("application", "configuration"),
            "slack", Set.of("domain", "application", "port.out", "adapter.sdk", "adapter.postgres",
                    "configuration"),
            "bootstrap", Set.of());

    @Test
    void containsOnlyTheNewRuntimeModules() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                Set.of("tool", "conversation", "mcp", "model", "storage", "web", "worker", "slack", "bootstrap"),
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
