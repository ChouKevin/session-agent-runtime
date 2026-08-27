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
    static final ArchRule SPRING_AI_STAYS_IN_MODEL_OR_BOOTSTRAP = noClasses()
            .that().resideOutsideOfPackages(
                    "com.java.system.sessionagent.model..",
                    "com.java.system.sessionagent.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule GOOGLE_AI_STAYS_IN_MODEL = noClasses()
            .that().resideOutsideOfPackage("com.java.system.sessionagent.model..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.google..");
}
