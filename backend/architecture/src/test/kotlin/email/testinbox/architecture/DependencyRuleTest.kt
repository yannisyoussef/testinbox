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
                email.testinbox.application.port.ApiKeyMetrics::class.java,
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

    @Test
    fun `the plaintext credential is rendered in exactly one place (ADR-032 §4)`() {
        // "Returned exactly once" is only true if exactly one call site can
        // produce the string at all. Any other caller — a log line, an error
        // message, a debug endpoint — would be a leak that compiles.
        val renderers =
            allClasses
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        // Both spellings: `ApiKeyFormat.render(publicId, secret)`
                        // produces the same string as the instance method, so
                        // guarding one leaves a side door open.
                        call.name == "render" &&
                            call.targetOwner.name in
                            setOf(
                                email.testinbox.domain.tenant.ApiKeyCredential::class.java.name,
                                email.testinbox.domain.tenant.ApiKeyFormat::class.java.name,
                            )
                    }
                }.map { it.owner.name }
                .toSet()

        // The DTO assembly in the controller, and the format object itself.
        val allowed =
            setOf(
                "email.testinbox.api.web.ApiKeyController",
                email.testinbox.domain.tenant.ApiKeyCredential::class.java.name,
            )
        // Guard the guard: if call resolution silently found nothing, every
        // assertion below would pass while proving nothing at all.
        check("email.testinbox.api.web.ApiKeyController" in renderers) {
            "no caller of ApiKeyCredential.render() was found — this rule is not actually checking anything"
        }
        val unexpected = renderers - allowed
        check(unexpected.isEmpty()) {
            "$unexpected renders a plaintext API credential. Only the single 201 response may (ADR-032 §4)."
        }
    }

    @Test
    fun `only the use case writes a managed credential (ADR-032)`() {
        // Minting is where provenance, scope checks, the audit record and the
        // metric all happen. A second minting path would be a credential with
        // no audit trail.
        //
        // Two calls have to be guarded, not one: generating the secret AND
        // writing the row. The rule originally covered only `generate`, so its
        // name claimed more than it checked — an adapter calling
        // `ApiKeyRepository.insert` directly would have passed.
        val writers =
            allClasses
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.name == email.testinbox.application.port.ApiKeyRepository::class.java.name &&
                            call.name == "insert"
                    }
                }.map { it.owner.name }
                .toSet()
        check(email.testinbox.application.usecase.CreateApiKey::class.java.name in writers) {
            "no caller of ApiKeyRepository.insert was found — this rule is not actually checking anything"
        }
        check(writers.size == 1) {
            "$writers write managed credential rows. Only CreateApiKey may: it is what records provenance, " +
                "enforces scopes and writes the audit event (ADR-032). Bootstrap provisioning goes through " +
                "ProvisioningRepository.ensureApiKey instead, which cannot create a MANAGED row (V4 check " +
                "constraint) and is covered by BootstrapTransitionTest."
        }
        val minters =
            allClasses
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.name == email.testinbox.domain.tenant.ApiKeyFormat::class.java.name &&
                            call.name == "generate"
                    }
                }.map { it.owner.name }
                .toSet()

        check(email.testinbox.application.usecase.CreateApiKey::class.java.name in minters) {
            "no caller of ApiKeyFormat.generate() was found — this rule is not actually checking anything"
        }
        val unexpected =
            minters.filterNot {
                it == email.testinbox.application.usecase.CreateApiKey::class.java.name ||
                    it.startsWith("email.testinbox.domain.tenant.")
            }
        check(unexpected.isEmpty()) {
            "$unexpected generates API credentials directly. Minting belongs to CreateApiKey, which is " +
                "what records provenance, enforces scopes and writes the audit event (ADR-032)."
        }
    }

    @Test
    fun `the verifier comparison is constant time (ADR-032 §3)`() {
        // Replacing `MessageDigest.isEqual` with `a == b` left the whole
        // application and architecture suite green, while `docs/quality/`
        // claimed constant-time verification was covered. A statistical timing
        // test would be flaky and prove little; pinning the call is cheap,
        // deterministic, and fails on exactly the edit that matters.
        val authenticator = email.testinbox.application.usecase.AuthenticateApiKey::class.java.name
        val calls =
            allClasses
                .single { it.name == authenticator }
                .methods
                .flatMap { it.callsFromSelf }

        val usesConstantTimeEquality =
            calls.any { it.targetOwner.name == java.security.MessageDigest::class.java.name && it.name == "isEqual" }
        check(usesConstantTimeEquality) {
            "AuthenticateApiKey no longer calls MessageDigest.isEqual. The verifier comparison must be " +
                "constant time (ADR-032 §3): String.equals returns early at the first differing byte, which " +
                "leaks how much of a guessed verifier was correct."
        }
        // And it must not compare strings the ordinary way, which is how the
        // constant-time call would quietly be replaced.
        val stringEquals = calls.filter { it.targetOwner.name == String::class.java.name && it.name == "equals" }
        check(stringEquals.isEmpty()) {
            "AuthenticateApiKey calls String.equals. Credential comparison must go through " +
                "MessageDigest.isEqual (ADR-032 §3)."
        }
    }

    @Test
    fun `audit events cannot carry credential material (TI-002 §14)`() {
        // The same argument as the metric-port rule, for the other sink: the
        // guarantee is structural — there is no field to put a secret in — and
        // this is what keeps it structural.
        val forbidden = listOf("secret", "hash", "verifier", "token", "credential", "password")
        // Derived from the sealed hierarchy rather than hand-listed: an event
        // type added later must be covered automatically, or the rule guards
        // exactly the cases that were already safe.
        val events = email.testinbox.application.port.AuditEvent::class.sealedSubclasses.map { it.java }
        check(events.size >= 3) {
            "found ${events.size} AuditEvent subtypes — this rule is not actually checking anything"
        }
        for (event in events) {
            for (field in event.declaredFields.filterNot { it.isSynthetic }) {
                val name = field.name.lowercase()
                // `contains`, not `endsWith`: `keyPreview` and `secretMaterial`
                // would both slip past a suffix match.
                check(forbidden.none { name.contains(it) }) {
                    "${event.simpleName}.${field.name} looks like credential material. Audit events carry " +
                        "identifiers an operator can act on, never anything that grants access (TI-002 §14)."
                }
            }
        }
    }

    @Test
    fun `only the idempotency coordinator claims or completes a record (ADR-033)`() {
        // ADR-033 §6 justifies the whole snapshot design on the premise that
        // the record is written by the application layer, in the same
        // transaction as the mutation. An adapter calling `claim` or
        // `complete` directly would put the only write that guarantees
        // "committed ⇒ recorded" outside that layer — against ADR-024, and
        // silently, because nothing about it would fail.
        //
        // `deleteExpired` is deliberately NOT covered: it writes no invariant,
        // and the retention sweep drives it from the API adapter the same way
        // it drives `WaitSlots.reapExpired`.
        val guarded = setOf("claim", "complete")
        val writers =
            allClasses
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.name ==
                            email.testinbox.application.port.IdempotencyRecords::class.java.name &&
                            call.name in guarded
                    }
                }.map { it.owner.name }
                .toSet()

        val coordinator = email.testinbox.application.idempotency.Idempotency::class.java.name
        // Guard the guard: if call resolution found nothing, the assertion
        // below would pass while proving nothing at all.
        check(coordinator in writers) {
            "no caller of IdempotencyRecords.claim/complete was found — this rule is not actually checking anything"
        }
        val unexpected = writers.filterNot { it == coordinator || it.startsWith("$coordinator$") }
        check(unexpected.isEmpty()) {
            "$unexpected claims or completes an idempotency record. Only Idempotency may: it is what keeps the " +
                "claim and the mutation in one transaction, so a committed mutation cannot exist without its " +
                "record (ADR-033 §2, §6)."
        }
    }
}
