package com.java.system.sessionagent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.java.system.sessionagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class RuntimeBoundaryTest {

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
    static final ArchRule MCP_SDK_STAYS_IN_MCP_SDK_ADAPTER = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.mcp.adapter.sdk..")
            .should().dependOnClassesThat().resideInAnyPackage("io.modelcontextprotocol..");

    @ArchTest
    static final ArchRule SPRING_AI_MCP_STAYS_IN_MCP_SDK_ADAPTER = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.mcp.adapter.sdk..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.mcp..");

    @ArchTest
    static final ArchRule MODEL_PROVIDER_TYPES_STAY_IN_SPRING_AI_ADAPTER_OR_BOOTSTRAP = noClasses()
            .that().resideOutsideOfPackages(
                    "com.java.system.sessionagent.model.adapter.springai..",
                    "com.java.system.sessionagent.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.google..",
                    "com.openai..",
                    "org.springframework.ai.google..",
                    "org.springframework.ai.openai..");

    @ArchTest
    static final ArchRule PROVIDER_DEPENDENCIES_DO_NOT_LEAK_TO_CORE_RUNTIME_MODULES = noClasses()
            .that().resideInAnyPackage(
                    "com.java.system.sessionagent.conversation.domain..",
                    "com.java.system.sessionagent.conversation.application..",
                    "com.java.system.sessionagent.conversation.port..",
                    "com.java.system.sessionagent.tool..",
                    "com.java.system.sessionagent.skill..",
                    "com.java.system.sessionagent.storage..",
                    "com.java.system.sessionagent.web..",
                    "com.java.system.sessionagent.worker..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.ai..",
                    "io.modelcontextprotocol..",
                    "com.google..",
                    "com.openai..",
                    "com.java.system.sessionagent.semantic..");

    @ArchTest
    static final ArchRule SLACK_SDK_STAYS_IN_SLACK_SDK_ADAPTER = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.slack.adapter.sdk..")
            .should().dependOnClassesThat().resideInAnyPackage("com.slack.api..");

    @ArchTest
    static final ArchRule CONVERSATION_DOMAIN_AND_PORTS_DO_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAnyPackage(
                    "com.java.system.sessionagent.conversation.domain..",
                    "com.java.system.sessionagent.conversation.port..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.java.system.sessionagent.conversation.application..",
                    "com.java.system.sessionagent.conversation.adapter..",
                    "com.java.system.sessionagent.conversation.configuration..");

    @ArchTest
    static final ArchRule SLACK_DOMAIN_AND_PORTS_DO_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAnyPackage(
                    "com.java.system.sessionagent.slack.domain..",
                    "com.java.system.sessionagent.slack.port..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.java.system.sessionagent.slack.application..",
                    "com.java.system.sessionagent.slack.adapter..",
                    "com.java.system.sessionagent.slack.configuration..");
}
