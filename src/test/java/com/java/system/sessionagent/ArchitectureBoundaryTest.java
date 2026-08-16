package com.java.system.sessionagent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.java.system.sessionagent")
class ArchitectureBoundaryTest {

    private static final String OLD_AGENT_PACKAGE = "com.java.system." + "agent..";

    @ArchTest
    static final ArchRule PRODUCTION_AND_TEST_CLASSES_NEVER_DEPEND_ON_OLD_AGENT = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage(OLD_AGENT_PACKAGE);
}
