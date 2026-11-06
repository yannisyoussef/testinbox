package email.testinbox.benchmark

import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FootprintWiring
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.JdbcStorageAdmission
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/**
 * Gate D counts only a production-faithful T1 (#120 verdict §2.3). These
 * tests prove the harness assembles the T1 production ALL assembles: the same
 * class, `readsFootprint` from `FootprintWiring.readsFootprint(declarations)`
 * exactly as `IngestionWiring.storageAdmission` computes it, and therefore the
 * same statement text. The runtime half of the proof is `ProtocolAssembly.probe`,
 * which every scenario runs against the real database before it is measured.
 *
 * Nothing here connects: the adapters are constructed over a data source that
 * is never opened.
 */
class ProtocolAssemblyTest {
    private val dataSource = DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never_opened")
    private val jdbc = JdbcClient.create(dataSource)
    private val template = TransactionTemplate(DataSourceTransactionManager(dataSource))

    private fun declarations(mode: AdmissionMode) =
        HarnessDeclarations.of(HarnessDeclarations.enforcement(mode, ReferenceMode.NO_LOCK), "bench_monitor")

    @Test
    fun `CHOSEN is production ALL with a declared filesystem`() {
        val d = declarations(AdmissionMode.CHOSEN)
        d.enforcement shouldBe StorageEnforcement.ALL
        d.filesystem.declared shouldBe true
        FootprintWiring.readsFootprint(d) shouldBe true
        EffectiveStoragePolicy.footprint(d).shouldNotBeNull()
    }

    @Test
    fun `the CHOSEN store is exactly the adapter IngestionWiring builds, reading the footprint`() {
        val d = declarations(AdmissionMode.CHOSEN)
        val store = ProtocolAssembly.admissionStore(jdbc, template, AdmissionMode.CHOSEN, ReferenceMode.NO_LOCK, d)
        // IngestionWiring.storageAdmission: JdbcStorageAdmission(jdbc, template, readsFootprint = FootprintWiring.readsFootprint(declarations))
        val production = JdbcStorageAdmission(jdbc, template, readsFootprint = FootprintWiring.readsFootprint(d))

        store.javaClass shouldBe JdbcStorageAdmission::class.java
        store.readsFootprint shouldBe true
        // CHOSEN is mode a: the switch is GLOBAL, so no partitioned statement exists.
        store.locking shouldBe email.testinbox.application.storage.AdmissionLocking.GLOBAL
        store.partitionedStatement shouldBe null
        store.snapshotStatement shouldBe production.snapshotStatement
        store.snapshotStatement shouldContain "storage_filesystem_observation"
        store.snapshotStatement shouldContain "fp_cohort"
        // The c10dd3d harness built the adapter without readsFootprint: a different, cheaper statement.
        store.snapshotStatement shouldNotBe JdbcStorageAdmission(jdbc, template).snapshotStatement
    }

    @Test
    fun `the NO_LOCK reference differs from production only by its lock step`() {
        val d = declarations(AdmissionMode.REFERENCE)
        d.enforcement shouldBe StorageEnforcement.TENANT_LIMITS
        val store = ProtocolAssembly.admissionStore(jdbc, template, AdmissionMode.REFERENCE, ReferenceMode.NO_LOCK, d)
        val chosen =
            ProtocolAssembly.admissionStore(
                jdbc,
                template,
                AdmissionMode.CHOSEN,
                ReferenceMode.NO_LOCK,
                declarations(AdmissionMode.CHOSEN),
            )

        store.javaClass shouldBe NoLockAdmissionStore::class.java
        store.readsFootprint shouldBe true
        store.snapshotStatement shouldBe chosen.snapshotStatement
    }
}
