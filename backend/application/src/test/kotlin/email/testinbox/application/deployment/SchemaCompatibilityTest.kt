package email.testinbox.application.deployment

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SchemaCompatibilityTest {
    private fun history(
        successful: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        present: Boolean = true,
    ) = SchemaHistory { AppliedSchema(successful, failed, present) }

    private fun statusOf(
        bundled: String?,
        successful: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        present: Boolean = true,
    ) = SchemaCompatibility(history(successful, failed, present), bundled?.let(::SchemaVersion)).status()

    @Test
    fun `a schema at the bundled version is compatible`() {
        val status = statusOf("3", successful = listOf("1", "2", "3"))
        status.compatible shouldBe true
        status.applied shouldBe "3"
    }

    @Test
    fun `a schema behind the bundled version refuses traffic`() {
        val status = statusOf("3", successful = listOf("1", "2"))
        status.compatible shouldBe false
        status.detail shouldContain "requires 3"
    }

    @Test
    fun `a schema ahead of the bundled version stays compatible - this is a rolled-back artifact`() {
        // ADR-029 §4 / ADR-028: forbidding this would make artifact rollback
        // impossible after any migration, which is the whole rollback story.
        val status = statusOf("2", successful = listOf("1", "2", "3"))
        status.compatible shouldBe true
        status.detail shouldContain "rolled-back artifact"
    }

    @Test
    fun `a failed migration in history refuses traffic even when the version looks new enough`() {
        val status = statusOf("3", successful = listOf("1", "2", "3"), failed = listOf("4"))
        status.compatible shouldBe false
        status.detail shouldContain "failed migrations"
    }

    @Test
    fun `an absent history refuses traffic - the migration job has not run`() {
        val status = statusOf("3", present = false)
        status.compatible shouldBe false
        status.detail shouldContain "have not run"
    }

    @Test
    fun `an empty history table refuses traffic`() {
        val status = statusOf("1", successful = emptyList(), present = true)
        status.compatible shouldBe false
        status.detail shouldContain "no successful migration"
    }

    @Test
    fun `an artifact bundling no migrations has nothing to require`() {
        statusOf(null, present = false).compatible shouldBe true
    }

    @Test
    fun `versions order numerically, not lexicographically`() {
        // The bug this pins: "10" < "9" as strings, so a node on schema 10
        // would report itself behind a bundled 9 and refuse all traffic.
        (SchemaVersion("10") > SchemaVersion("9")) shouldBe true
        (SchemaVersion("1.10") > SchemaVersion("1.9")) shouldBe true
        (SchemaVersion("2") > SchemaVersion("1.99")) shouldBe true
        SchemaVersion("1.0").compareTo(SchemaVersion("1")) shouldBe 0
        statusOf("9", successful = listOf("9", "10")).compatible shouldBe true
    }
}
