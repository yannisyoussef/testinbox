package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import email.testinbox.architecture.fixtures.RogueGuardOverrideFixture
import email.testinbox.architecture.fixtures.RoguePartitionedConstructionFixture
import email.testinbox.persistence.JdbcStorageAdmission
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The share-partitioned T1 (PROPOSED ADR-035 amendment; review #136 A6): its lock
 * protocol is chosen only by the declared switch, in the one wiring that builds
 * T1 (or the §11 benchmark), and no main-code subclass may weaken its guard or
 * its locks. Each rule is proved able to fail on a rogue fixture.
 */
class PartitionedT1RuleTest {
    companion object {
        private lateinit var allClasses: JavaClasses

        @JvmStatic
        @BeforeAll
        fun importClasses() {
            allClasses = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox")
        }

        private const val ADAPTER = "email.testinbox.persistence.JdbcStorageAdmission"

        /** Where T1's adapter may be built: the ingestion wiring, and the §11 benchmark CLI (not a deployable). */
        private val CONSTRUCTION_SITES =
            setOf(
                ADAPTER,
                "email.testinbox.ingestion.config.IngestionWiring",
                "email.testinbox.benchmark.ProtocolAssembly",
                "email.testinbox.benchmark.ProtocolAssembly\$Companion",
                "email.testinbox.benchmark.NoLockAdmissionStore",
                // The benchmark CLI's matrix records production's statement texts for its diagnostics.
                "email.testinbox.benchmark.Harness\$Matrix",
            )

        /** Who may name the partitioned protocol in code: the adapter that implements it, and the benchmark's declarations. */
        private val PARTITIONED_REFERENCES =
            setOf(ADAPTER, "email.testinbox.benchmark.HarnessDeclarations", "email.testinbox.application.storage.AdmissionLocking")

        private val HOOKS = setOf("partitionGuard", "acquireSharedAdmissionLock", "acquireWorkspaceLocks")
    }

    private fun constructionRule(): ArchRule =
        noClasses()
            .that(
                com.tngtech.archunit.base.DescribedPredicate.describe("are not a T1 construction site") { c: JavaClass ->
                    c.name !in
                        CONSTRUCTION_SITES
                },
            ).should()
            .callConstructorWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                        com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .type(JdbcStorageAdmission::class.java),
                    ),
                ),
            ).because("T1's adapter, and with it its lock protocol, is built only from the validated declarations (ADR-035 §14)")

    private fun partitionedReferenceRule(): ArchRule =
        noClasses()
            .that(
                com.tngtech.archunit.base.DescribedPredicate.describe("do not implement the protocol") { c: JavaClass ->
                    c.name !in
                        PARTITIONED_REFERENCES
                },
            ).should()
            .accessField(email.testinbox.application.storage.AdmissionLocking::class.java, "COHORT_PARTITIONED")
            .because("the share-partitioned T1 is selected only by testinbox.storage.admission-locking, never hard-coded")

    private fun hookRule(): ArchRule =
        methods()
            .that(
                com.tngtech.archunit.base.DescribedPredicate.describe(
                    "are T1 lock or guard hooks",
                ) { m: com.tngtech.archunit.core.domain.JavaMethod ->
                    m.name in
                        HOOKS
                },
            ).should()
            .beDeclaredIn(JdbcStorageAdmission::class.java)
            .because("no main-code subclass may weaken the partition guard or its locks; only test mutants override them")

    @Test
    fun `only the ingestion wiring and the benchmark build T1's adapter`() = constructionRule().check(allClasses)

    @Test
    fun `only the adapter and the benchmark's declarations name COHORT_PARTITIONED`() = partitionedReferenceRule().check(allClasses)

    @Test
    fun `no main-code subclass overrides the partition guard or the T1 locks`() = hookRule().check(allClasses)

    @Test
    fun `each rule catches its rogue fixture, so it can fail`() {
        val rogue =
            ClassFileImporter().importClasses(
                RoguePartitionedConstructionFixture::class.java,
                RogueGuardOverrideFixture::class.java,
            )
        for ((rule, expected) in listOf(
            constructionRule() to "JdbcStorageAdmission.<init>",
            partitionedReferenceRule() to "COHORT_PARTITIONED",
            hookRule() to "partitionGuard",
        )) {
            val failure = runCatching { rule.check(rogue) }.exceptionOrNull()
            check(failure is AssertionError && expected in failure.message.orEmpty()) { "the rule did not catch its fixture: $failure" }
        }
    }
}
