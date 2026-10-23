package email.testinbox.domain.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The refusal reasons are a closed set that the API reads back from
 * `inbox_storage.last_refusal_reason` and FAILS CLOSED on when unknown
 * (`JdbcStorageVisibility`). Adding one is therefore reader-first: the API
 * digest that knows it is deployed and floored before any ingestion artifact
 * may write it (`docs/dev/rollback.md`, "Adding a StorageRefusalReason").
 * Changing this list means changing this test, which is the point: the
 * procedure cannot be skipped by accident.
 */
class StorageRefusalReasonTest {
    @Test
    fun `the closed set is exactly the three ADR-035 §4 ceilings, narrowest first`() {
        StorageRefusalReason.entries.map { it.name } shouldBe listOf("INBOX_LIMIT", "WORKSPACE_LIMIT", "SERVICE_CAPACITY")
        StorageRefusalReason.entries.map { it.scope } shouldBe listOf(StorageScope.INBOX, StorageScope.WORKSPACE, StorageScope.GLOBAL)
    }
}
