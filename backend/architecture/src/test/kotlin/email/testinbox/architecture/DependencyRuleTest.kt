package email.testinbox.architecture

import com.tngtech.archunit.base.DescribedPredicate.describe
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * ADR-024 dependency rule: domain ← application ← adapters, enforced
 * mechanically. These tests protect the boundaries listed in
 * docs/architecture/component-architecture.md.
 */
class DependencyRuleTest {
    companion object {
        private lateinit var allClasses: JavaClasses

        @JvmStatic
        @BeforeAll
        fun import() {
            allClasses =
                ClassFileImporter()
                    .withImportOption(ImportOption.DoNotIncludeTests())
                    .importPackages("email.testinbox")
        }
    }

    @Test
    fun `domain depends on nothing but itself and the platform`() {
        classes()
            .that()
            .resideInAPackage("email.testinbox.domain..")
            .should()
            .onlyDependOnClassesThat(
                describe("are domain, JDK, or Kotlin stdlib classes") { target: JavaClass ->
                    target.packageName.startsWith("email.testinbox.domain") ||
                        target.packageName.startsWith("java") ||
                        target.packageName.startsWith("kotlin") ||
                        target.packageName.startsWith("org.jetbrains.annotations")
                },
            ).check(allClasses)
    }

    @Test
    fun `application depends only on domain, slf4j, JDK and Kotlin`() {
        classes()
            .that()
            .resideInAPackage("email.testinbox.application..")
            .should()
            .onlyDependOnClassesThat(
                describe("are application, domain, slf4j, JDK, or Kotlin classes") { target: JavaClass ->
                    target.packageName.startsWith("email.testinbox.application") ||
                        target.packageName.startsWith("email.testinbox.domain") ||
                        target.packageName.startsWith("org.slf4j") ||
                        target.packageName.startsWith("java") ||
                        target.packageName.startsWith("kotlin") ||
                        target.packageName.startsWith("org.jetbrains.annotations")
                },
            ).check(allClasses)
    }

    @Test
    fun `domain and application are free of Spring, JPA, SQL and provider types`() {
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.domain..")
            .or()
            .resideInAPackage("email.testinbox.application..")
            .should()
            .dependOnClassesThat(
                describe("are framework/provider classes") { target: JavaClass ->
                    listOf(
                        "org.springframework",
                        "jakarta.persistence",
                        "jakarta.servlet",
                        "java.sql",
                        "javax.sql",
                        "org.postgresql",
                        "software.amazon",
                        "org.subethamail",
                        "jakarta.mail",
                        "org.flywaydb",
                        "com.fasterxml.jackson",
                    ).any { target.packageName.startsWith(it) }
                },
            ).check(allClasses)
    }

    @Test
    fun `adapters never depend on other adapters`() {
        val adapterPackages =
            mapOf(
                "persistence" to "email.testinbox.persistence..",
                "storage" to "email.testinbox.storage..",
                "notification" to "email.testinbox.notification..",
            )
        for ((name, pkg) in adapterPackages) {
            val others = adapterPackages.filterKeys { it != name }.values.toTypedArray()
            noClasses()
                .that()
                .resideInAPackage(pkg)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*others)
                .because("infrastructure adapters depend inward only (ADR-024)")
                .check(allClasses)
        }
    }

    @Test
    fun `entry-point adapters do not depend on each other`() {
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("email.testinbox.ingestion..")
            .check(allClasses)
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.ingestion..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("email.testinbox.api..")
            .check(allClasses)
    }

    @Test
    fun `ingestion never touches persistence or storage implementations directly`() {
        // The gateway invokes application use cases (ADR-024); its only legal
        // references to persistence/storage are the wiring of port implementations.
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.ingestion..")
            .and()
            .resideOutsideOfPackage("email.testinbox.ingestion.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "email.testinbox.persistence..",
                "email.testinbox.storage..",
            ).check(allClasses)
    }

    @Test
    fun `no content-based dedup types leak into domain (ADR-019 guard)`() {
        // The only provider fingerprint retained is providerMessageId as an opaque string.
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.domain..")
            .should()
            .dependOnClassesThat(
                describe("are provider SDK types") { target: JavaClass ->
                    target.packageName.startsWith("software.amazon") ||
                        target.packageName.startsWith("org.subethamail")
                },
            ).check(allClasses)
    }
}
