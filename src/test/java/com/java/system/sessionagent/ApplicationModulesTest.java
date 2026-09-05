package com.java.system.sessionagent;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModulesTest {

    @Test
    void containsOnlyTheNewRuntimeModules() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                Set.of("tool", "conversation", "mcp", "model", "storage", "web", "worker", "bootstrap"),
                modules.stream().map(module -> module.getIdentifier().toString()).collect(Collectors.toSet()));
        modules.verify();
    }

    @Test
    void declaresTheClosedRuntimeDependencyMatrix() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                Map.of(
                        "tool", Set.of(),
                        "conversation", Set.of("tool"),
                        "mcp", Set.of("tool"),
                        "model", Set.of("conversation", "tool"),
                        "storage", Set.of("conversation", "tool"),
                        "web", Set.of("conversation", "tool"),
                        "worker", Set.of("conversation"),
                        "bootstrap", Set.of("tool", "conversation", "mcp", "model", "storage", "web", "worker")),
                modules.stream().collect(Collectors.toMap(
                        module -> module.getIdentifier().toString(),
                        module -> allowedModuleNames(module, modules))));
        assertTrue(modules.stream().noneMatch(module -> module.isOpen()));
    }

    @Test
    void declaresOnlyTheRequiredNamedModuleContracts() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);

        assertEquals(
                Set.of("domain", "port"),
                modules.getModuleByName("tool").orElseThrow().getNamedInterfaces().stream()
                        .filter(namedInterface -> !namedInterface.isUnnamed())
                        .map(namedInterface -> namedInterface.getName())
                        .collect(Collectors.toSet()));
        assertEquals(Set.of("tool :: domain", "tool :: port"), allowedNamedDependencies("conversation", modules));
        assertEquals(Set.of("tool :: port"), allowedNamedDependencies("mcp", modules));
        assertEquals(
                Set.of("conversation :: domain", "conversation :: port.out", "tool :: domain", "tool :: port"),
                allowedNamedDependencies("model", modules));
        assertEquals(
                Set.of("conversation :: domain", "conversation :: port.in", "conversation :: port.out", "tool :: domain"),
                allowedNamedDependencies("storage", modules));
        assertEquals(
                Set.of("conversation :: domain", "conversation :: port.in", "tool :: domain"),
                allowedNamedDependencies("web", modules));
        assertEquals(
                Set.of("conversation :: domain", "conversation :: port.in", "conversation :: port.out"),
                allowedNamedDependencies("worker", modules));
        assertEquals(
                Set.of("tool :: port", "conversation :: application", "conversation :: domain",
                        "conversation :: port.in", "conversation :: port.out"),
                allowedNamedDependencies("bootstrap", modules));
    }

    private static Set<String> allowedModuleNames(ApplicationModule module, ApplicationModules modules) {
        return module.getAllowedDependencies(modules).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
                .collect(Collectors.toSet());
    }

    private static Set<String> allowedNamedDependencies(String moduleName, ApplicationModules modules) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getAllowedDependencies(modules).stream()
                .filter(dependency -> !dependency.getTargetNamedInterface().isUnnamed())
                .map(dependency -> dependency.getTargetModule().getIdentifier()
                        + " :: " + dependency.getTargetNamedInterface().getName())
                .collect(Collectors.toSet());
    }
}
