package com.moveinsync.rostersync;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.moveinsync.rostersync");

    @Test
    void domainHasNoFrameworkDatabaseOrOuterLayerDependencies() {
        noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "..infrastructure..", "..application..", "..api..")
                .check(CLASSES);
    }

    @Test
    void apiNeverCallsPersistenceDirectly() {
        noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
                .resideInAPackage("..infrastructure.persistence..").check(CLASSES);
    }

    @Test
    void tenantRepositoryMethodsRequireAnExplicitTenantId() {
        for (var type : CLASSES) {
            if (type.isInterface() && type.getSimpleName().endsWith("Repository")) {
                for (var method : type.getMethods()) {
                    assertThat(method.getRawParameterTypes()).as("Tenant parameter on %s", method.getFullName())
                            .anyMatch(parameter -> parameter.isEquivalentTo(UUID.class));
                }
            }
        }
    }
}
