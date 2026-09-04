package com.java.system.sessionagent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.java.system.sessionagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class SpringAiBoundaryTest {

    @ArchTest
    static final ArchRule SPRING_AI_STAYS_IN_MODEL_BOOTSTRAP_OR_MCP = noClasses()
            .that().resideOutsideOfPackages(
                    "com.java.system.sessionagent.model..",
                    "com.java.system.sessionagent.bootstrap..",
                    "com.java.system.sessionagent.mcp..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule SPRING_AI_CHAT_APIS_STAY_IN_MODEL_OR_BOOTSTRAP = noClasses()
            .that().resideOutsideOfPackages(
                    "com.java.system.sessionagent.model..",
                    "com.java.system.sessionagent.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.chat..");

    @ArchTest
    static final ArchRule MCP_SDK_STAYS_IN_MCP = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.mcp..")
            .should().dependOnClassesThat().resideInAnyPackage("io.modelcontextprotocol..");

    @ArchTest
    static final ArchRule SPRING_AI_MCP_STAYS_IN_MCP = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.mcp..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.mcp..");

    @ArchTest
    static final ArchRule GOOGLE_SPECIFIC_TYPES_STAY_IN_BOOTSTRAP = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.google..",
                    "org.springframework.ai.google..");

    @ArchTest
    static final ArchRule PROVIDER_DEPENDENCIES_DO_NOT_LEAK_TO_CORE_RUNTIME_MODULES = noClasses()
            .that().resideInAnyPackage(
                    "com.java.system.sessionagent.conversation.domain..",
                    "com.java.system.sessionagent.conversation.application..",
                    "com.java.system.sessionagent.conversation.port..",
                    "com.java.system.sessionagent.tool..",
                    "com.java.system.sessionagent.storage..",
                    "com.java.system.sessionagent.web..",
                    "com.java.system.sessionagent.worker..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.ai..",
                    "io.modelcontextprotocol..",
                    "com.google..",
                    "com.java.system.sessionagent.semantic..");
}
