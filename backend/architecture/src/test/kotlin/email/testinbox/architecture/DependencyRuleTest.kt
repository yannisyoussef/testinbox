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
                "observability" to "email.testinbox.observability..",
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
        val entryPoints = listOf("email.testinbox.api..", "email.testinbox.ingestion..", "email.testinbox.migrator..")
        for (entryPoint in entryPoints) {
            val others = entryPoints.filterNot { it == entryPoint }.toTypedArray()
            noClasses()
                .that()
                .resideInAPackage(entryPoint)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*others)
                .because("each deployable is independently deployable (ADR-001, ADR-029)")
                .check(allClasses)
        }
    }

    @Test
    fun `the migration executor knows nothing but Flyway and its own configuration (ADR-029)`() {
        // The migrator exists to fail for exactly one reason. It carries the
        // persistence module's SQL resources at runtime and must not compile
        // against a single TestInbox class: a migrator that could reach the
        // domain would eventually be asked to seed, backfill or fix data, and
        // then the one deployment step that cannot be rolled back would have
        // application logic in it.
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.migrator..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "email.testinbox.domain..",
                "email.testinbox.application..",
                "email.testinbox.persistence..",
                "email.testinbox.storage..",
                "email.testinbox.notification..",
                "email.testinbox.observability..",
            ).check(allClasses)
    }

    @Test
    fun `the migration history is read by exactly one adapter (ADR-029)`() {
        // The port may only be implemented where the system of record lives. A
        // second implementation — an entry point reading flyway_schema_history
        // for itself — is how the two deployables start disagreeing about
        // whether the environment is safe to serve.
        classes()
            .that()
            .implement(email.testinbox.application.deployment.SchemaHistory::class.java)
            .should()
            .resideInAPackage("email.testinbox.persistence..")
            .because("the schema history is a persistence concern (ADR-024, ADR-029 §4)")
            .check(allClasses)
    }

    @Test
    fun `entry points consume the schema verdict, never the raw versions (ADR-029)`() {
        // SchemaVersion and AppliedSchema are the inputs to the comparison, and
        // an adapter that holds them is an adapter about to redo it. Entry
        // points get SchemaStatus — already decided, already rendered — so
        // this rule fails the moment a second copy of the policy appears.
        noClasses()
            .that()
            .resideInAnyPackage("email.testinbox.api..", "email.testinbox.ingestion..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(email.testinbox.application.deployment.SchemaVersion::class.java.name)
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(email.testinbox.application.deployment.AppliedSchema::class.java.name)
            .because("schema compatibility is decided once, in the application layer (ADR-024, ADR-029 §4)")
            .check(allClasses)
    }

    @Test
    fun `metric ports cannot carry a caller-controlled value (ADR-027 §14)`() {
        // Bounded cardinality is a SECURITY property: a label whose values a
        // caller chooses lets that caller choose how much memory the metrics
        // backend spends. Today every metric port takes enums, durations and
        // booleans, so there is no parameter an identifier could travel
        // through — and this rule is what keeps that true. Adding a
        // `workspaceId: String` to a port would otherwise compile, pass every
        // other rule, and only be caught if whoever added it also remembered
        // to exercise it in the cardinality test.
        val metricPorts =
            listOf(
                email.testinbox.application.port.LimitMetrics::class.java,
                email.testinbox.application.port.InboxMetrics::class.java,
                email.testinbox.application.port.InboundMetrics::class.java,
                email.testinbox.application.port.WaitMetrics::class.java,
                email.testinbox.application.port.NotifierMetrics::class.java,
                email.testinbox.application.port.BlobStoreMetrics::class.java,
                email.testinbox.application.port.SmtpMetrics::class.java,
            )
        val allowed =
            setOf(
                java.time.Duration::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
        for (port in metricPorts) {
            // Kotlin emits synthetic `access$...` bridges for interface default
            // implementations; they take the interface itself and are not part
            // of the port's surface.
            for (method in port.declaredMethods.filterNot { it.isSynthetic || it.isBridge || "$" in it.name }) {
                for (parameter in method.parameterTypes) {
                    val ok = parameter.isEnum || parameter in allowed
                    check(ok) {
                        "${port.simpleName}.${method.name} takes a ${parameter.simpleName}. Metric port " +
                            "parameters must be enums, durations or numbers: anything caller-controlled " +
                            "(workspace id, api key, inbox id, address, correlation id) becomes an " +
                            "unbounded Prometheus label (ADR-027 §14)."
                    }
                }
            }
        }
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
    fun `limit enforcement lives in the application layer, not in an adapter (ADR-027)`() {
        // A limiter or quota check owned by an HTTP filter could not protect the
        // independently deployed ingestion gateway, and a quota check duplicated
        // in an adapter drifts across two deployables. Adapters may map and
        // render; the decision belongs to exactly one use case.
        noClasses()
            .that()
            .resideInAPackage("email.testinbox.api..")
            .or()
            .resideInAPackage("email.testinbox.ingestion..")
            .should()
            .beAssignableTo(email.testinbox.application.port.RateLimiter::class.java)
            .orShould()
            .beAssignableTo(email.testinbox.application.port.WorkspaceQuotaState::class.java)
            .orShould()
            .beAssignableTo(email.testinbox.application.port.WaitSlots::class.java)
            .because("limit decisions are application-layer concerns (ADR-024, ADR-027 §3)")
            .check(allClasses)
    }

    @Test
    fun `the limit domain stays free of framework and storage types`() {
        classes()
            .that()
            .resideInAPackage("email.testinbox.domain.limits..")
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
