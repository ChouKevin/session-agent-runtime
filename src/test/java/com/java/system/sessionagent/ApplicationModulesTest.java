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
                Set.of("tool", "conversation", "semantic", "mcp", "model", "storage", "web", "worker", "bootstrap"),
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
                        "semantic", Set.of("tool"),
                        "mcp", Set.of("tool"),
                        "model", Set.of("conversation", "tool"),
                        "storage", Set.of("conversation"),
                        "web", Set.of("conversation"),
                        "worker", Set.of("conversation"),
                        "bootstrap", Set.of("tool", "conversation", "semantic", "model", "storage", "web", "worker")),
                modules.stream().collect(Collectors.toMap(
                        module -> module.getIdentifier().toString(),
                        module -> allowedModuleNames(module, modules))));
        assertTrue(modules.stream().noneMatch(ApplicationModule::isOpen));
    }

    @Test
    void exposesOnlyToolContractsRequiredByConversation() {
        ApplicationModules modules = ApplicationModules.of(SessionAgentRuntimeApplication.class);
        ApplicationModule conversation = modules.getModuleByName("conversation").orElseThrow();
        ApplicationModule mcp = modules.getModuleByName("mcp").orElseThrow();
        ApplicationModule tool = modules.getModuleByName("tool").orElseThrow();

        assertEquals(
                Set.of("tool :: domain", "tool :: application"),
                conversation.getAllowedDependencies(modules).stream()
                        .map(dependency -> dependency.getTargetModule().getIdentifier()
                                + " :: " + dependency.getTargetNamedInterface().getName())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of("tool :: port"),
                mcp.getAllowedDependencies(modules).stream()
                        .map(dependency -> dependency.getTargetModule().getIdentifier()
                                + " :: " + dependency.getTargetNamedInterface().getName())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of("domain", "application", "json", "port"),
                tool.getNamedInterfaces().stream()
                        .filter(namedInterface -> !namedInterface.isUnnamed())
                        .map(namedInterface -> namedInterface.getName())
                        .collect(Collectors.toSet()));
    }

    private static Set<String> allowedModuleNames(ApplicationModule module, ApplicationModules modules) {
        return module.getAllowedDependencies(modules).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
                .collect(Collectors.toSet());
    }
}
