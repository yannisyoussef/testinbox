package email.testinbox.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.properties.HasOwner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.storage.AdmissionAccounting
import email.testinbox.architecture.fixtures.RogueAdmissionLockOverrideFixture
import email.testinbox.architecture.fixtures.RogueEscrowConstructionFixture
import email.testinbox.architecture.fixtures.RogueRegrantOverrideFixture
import email.testinbox.persistence.EscrowSlowPath
import email.testinbox.persistence.EscrowSql
import email.testinbox.persistence.JdbcEscrowAdmission
import email.testinbox.persistence.JdbcStorageAdmission
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The slot-lane escrow PROTOTYPE's boundaries (ADR-038 PROPOSED;
 * structural-admission-design.md §7, §12.1 "the ArchUnit rules the prototype
 * touches"), each proved able to fail on a rogue fixture, as [PartitionedT1RuleTest]:
 * - the escrow adapters are built only by the storage benchmark (construction sites);
 * - only `DeploymentSafety`, the wiring and the benchmark name `ESCROW`;
 * - only the `StorageAdmission` use case (its [email.testinbox.application.usecase.EscrowAdmission]
 *   part) and `RefillStorageEscrow` call [StorageEscrowStore], so the API cannot reach
 *   spend or refill;
 * - no main-code subclass overrides `acquireAdmissionLock` or the strategy's hooks
 *   (the drain fence, the scope locks, the regrant), the benchmark's §11 NO_LOCK
 *   reference excepted, as it always was;
 * - only [EscrowSlowPath] reads [EscrowSql.REGRANT].
 */
class EscrowRuleTest {
    companion object {
        private lateinit var allClasses: JavaClasses

        @JvmStatic
        @BeforeAll
        fun importClasses() {
            allClasses = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox")
        }

        /** The storage benchmark CLI (not a deployable) assembles the prototype; nothing else does. */
        private val CONSTRUCTION_SITES =
            setOf(
                "email.testinbox.persistence.JdbcEscrowAdmission",
                "email.testinbox.persistence.EscrowSlowPath",
                "email.testinbox.benchmark.ProtocolAssembly",
                "email.testinbox.benchmark.ProtocolAssembly\$Companion",
            )

        /** Who may name ESCROW: the enum, DeploymentSafety's storage checks, and the benchmark's declarations. */
        private val ESCROW_REFERENCES =
            setOf(
                "email.testinbox.application.storage.AdmissionAccounting",
                "email.testinbox.application.deployment.StorageEnforcementSafety",
                "email.testinbox.benchmark.HarnessDeclarations",
            )

        /** The ONE use case that spends (StorageAdmission, through its escrow part) and the keeper's use case. */
        private val STORE_CALLERS =
            setOf(
                "email.testinbox.application.usecase.EscrowAdmission",
                "email.testinbox.application.usecase.RefillStorageEscrow",
                // The §11 benchmark's timing decorator: it only forwards, around the use case's own call.
                "email.testinbox.benchmark.TimingEscrowStore",
            )

        /** The port's operations, by name: an implementation's own helpers are not calls into the port. */
        private val STORE_OPERATIONS =
            StorageEscrowStore::class.java.declaredMethods
                .map { it.name }
                .toSet()

        private val HOOKS = setOf("acquireAdmissionLock", "escrowDrainFence", "escrowLockScopes", "escrowRegrant")

        /** Where each hook may be declared: its owner, and for the lock alone the §11 NO_LOCK measurement reference. */
        private val HOOK_OWNERS =
            setOf(
                JdbcStorageAdmission::class.java.name,
                EscrowSlowPath::class.java.name,
                "email.testinbox.benchmark.NoLockAdmissionStore",
            )

        private val REGRANT_READERS = setOf(EscrowSlowPath::class.java.name, EscrowSql::class.java.name)
    }

    private fun outside(
        names: Set<String>,
        description: String,
    ) = DescribedPredicate.describe(description) { c: JavaClass -> c.name !in names }

    private fun constructionRule(): ArchRule =
        noClasses()
            .that(outside(CONSTRUCTION_SITES, "are not an escrow construction site"))
            .should()
            .callConstructorWhere(
                JavaCall.Predicates.target(
                    HasOwner.Predicates.With.owner(
                        DescribedPredicate.describe("an escrow adapter") { c: JavaClass ->
                            c.isEquivalentTo(JdbcEscrowAdmission::class.java) || c.isEquivalentTo(EscrowSlowPath::class.java)
                        },
                    ),
                ),
            ).because("the slot-lane escrow prototype is assembled only by the storage benchmark (ADR-038 PROPOSED)")

    private fun escrowReferenceRule(): ArchRule =
        noClasses()
            .that(outside(ESCROW_REFERENCES, "are not allowed to name ESCROW"))
            .should()
            .accessField(AdmissionAccounting::class.java, "ESCROW")
            .because("the escrow is selected only by testinbox.storage.admission-accounting, never hard-coded (§7)")

    private fun storeCallerRule(): ArchRule =
        noClasses()
            .that(outside(STORE_CALLERS, "are not the StorageAdmission use case or RefillStorageEscrow"))
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("the target is a StorageEscrowStore operation") { call: JavaCall<*> ->
                    call.targetOwner.isAssignableTo(StorageEscrowStore::class.java) && call.name in STORE_OPERATIONS
                },
            ).because("only the one admission use case spends and only the keeper's use case refills, so no API path reaches either (§7)")

    private fun hookRule(): ArchRule =
        methods()
            .that(DescribedPredicate.describe("are T1 lock or escrow strategy hooks") { m: JavaMethod -> m.name in HOOKS })
            .should(
                com.tngtech.archunit.lang.conditions.ArchConditions.be(
                    DescribedPredicate.describe("declared by their owner") { m: JavaMethod ->
                        m.owner.name in HOOK_OWNERS && (m.name == "acquireAdmissionLock" || !m.owner.name.endsWith("NoLockAdmissionStore"))
                    },
                ),
            ).because(
                "no main-code subclass may skip the admission lock, the drain fence, the scope locks or the regrant after the insert (H1)",
            )

    private fun regrantRule(): ArchRule =
        noClasses()
            .that(outside(REGRANT_READERS, "are not the slow-path strategy"))
            .should()
            .accessField(EscrowSql::class.java, "REGRANT")
            .because("the global regrant is one definer call, issued only after the slow path's insert or by the keeper (§7)")

    @Test
    fun `only the storage benchmark builds the escrow adapters`() = constructionRule().check(allClasses)

    @Test
    fun `only DeploymentSafety, the wiring and the benchmark name ESCROW`() = escrowReferenceRule().check(allClasses)

    @Test
    fun `only the admission and refill use cases call the escrow store`() = storeCallerRule().check(allClasses)

    @Test
    fun `no main-code subclass overrides acquireAdmissionLock or the escrow strategy's hooks`() = hookRule().check(allClasses)

    @Test
    fun `only the slow-path strategy reads the regrant statement`() = regrantRule().check(allClasses)

    @Test
    fun `each rule catches its rogue fixture, so it can fail`() {
        val rogue =
            ClassFileImporter().importClasses(
                RogueEscrowConstructionFixture::class.java,
                RogueRegrantOverrideFixture::class.java,
                RogueAdmissionLockOverrideFixture::class.java,
            )
        for ((rule, expected) in listOf(
            constructionRule() to "JdbcEscrowAdmission.<init>",
            escrowReferenceRule() to "ESCROW",
            storeCallerRule() to "refillGlobal",
            hookRule() to "RogueRegrantOverrideFixture.escrowRegrant",
            hookRule() to "RogueAdmissionLockOverrideFixture.acquireAdmissionLock",
            regrantRule() to "REGRANT",
        )) {
            val failure = runCatching { rule.check(rogue) }.exceptionOrNull()
            check(
                failure is AssertionError && expected in failure.message.orEmpty(),
            ) { "the rule did not catch its fixture ($expected): $failure" }
        }
    }
}
