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
            if (type.isInterface() && type.getPackageName().contains(".infrastructure.persistence")
                    && (type.getSimpleName().endsWith("Repository") || type.getSimpleName().endsWith("RepositoryWrites"))) {
                for (var method : type.getMethods()) {
                    if (type.getSimpleName().equals("AppUserRepository") && method.getName().equals("findByUsername")
                            && method.getRawParameterTypes().size() == 1
                            && method.getRawParameterTypes().getFirst().isEquivalentTo(String.class)) continue;
                    assertThat(method.getRawParameterTypes()).as("Tenant parameter on %s", method.getFullName())
                            .anyMatch(parameter -> parameter.isEquivalentTo(UUID.class));
                    try {
                        assertThat(java.util.Arrays.stream(method.reflect().getParameters())
                                .anyMatch(parameter -> parameter.getType() == UUID.class && parameter.getName().equals("tenantId")))
                                .as("UUID tenantId parameter on %s", method.getFullName()).isTrue();
                    } catch (IllegalStateException exception) {
                        throw new AssertionError("Cannot inspect repository method", exception);
                    }
                }
            }
        }
    }
}
