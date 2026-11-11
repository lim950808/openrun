package dev.openrun.api

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0003 boundary rules. Module classes are on the apps/api test runtime classpath. */
class ArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.openrun")

    @Test
    fun `importer sees every module`() {
        listOf(
            "dev.openrun.booking",
            "dev.openrun.payment",
            "dev.openrun.outbox",
            "dev.openrun.catalog",
        ).forEach { assertThat(classes.containPackage(it)).describedAs(it).isTrue() }
    }

    @Test
    fun `JDBC modules do not touch JPA`() {
        noClasses().that().resideInAnyPackage("dev.openrun.booking..", "dev.openrun.payment..", "dev.openrun.outbox..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data.jpa..", "org.hibernate..")
            .check(classes)
    }

    @Test
    fun `catalog does not depend on hot path modules`() {
        noClasses().that().resideInAPackage("dev.openrun.catalog..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("dev.openrun.booking..", "dev.openrun.payment..")
            .check(classes)
    }
}
