package com.havita.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.havita", importOptions = ImportOption.DoNotIncludeTests.class)
public class ModularMonolithArchitectureTest {

    @ArchTest
    static final ArchRule controllersShouldBeNamedProperly = classes()
            .that().resideInAPackage("..api..")
            .and().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule domainExceptionsShouldExtendBase = classes()
            .that().resideInAPackage("..exception..")
            .and().haveSimpleNameEndingWith("Exception")
            .and().doNotHaveSimpleName("DomainException")
            .should().beAssignableTo(com.havita.common.exception.DomainException.class);

    @ArchTest
    static final ArchRule commonShouldNotDependOnSpecificModules = noClasses()
            .that().resideInAPackage("com.havita.common..")
            .should().dependOnClassesThat().resideInAPackage("com.havita.modules..");
}
