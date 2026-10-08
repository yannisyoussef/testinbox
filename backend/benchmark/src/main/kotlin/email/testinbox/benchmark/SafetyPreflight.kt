package email.testinbox.benchmark

/** What the harness established about its target before touching it. */
data class PreflightFacts(
    /** The value of [SafetyPreflight.ACK_VARIABLE] in the environment, or null. */
    val acknowledgement: String?,
    val databaseName: String,
    /** Rows in the tenant tables (`workspace`, `inbox`, `message`) at start; 0 when a table does not exist yet. */
    val tenantRowCounts: Map<String, Long>,
    /** True only when THIS run created the database through the maintenance connection (or started the local container). */
    val createdByHarness: Boolean,
)

sealed interface PreflightDecision {
    data class Allowed(
        val proof: String,
    ) : PreflightDecision

    data class Refused(
        val reasons: List<String>,
    ) : PreflightDecision
}

/**
 * The benchmark destroys and rewrites every tenant table of its target. It
 * therefore refuses to start unless ALL of these hold:
 *
 * 1. the operator acknowledged it, with the exact value of [ACK_VALUE] in
 *    [ACK_VARIABLE] (a flag or a shorter value is not an acknowledgement);
 * 2. the database name contains `bench`;
 * 3. the database holds no tenant rows, or this run created it.
 *
 * A database that already has tenant rows is never seeded, whatever the
 * acknowledgement says: the acknowledgement covers a benchmark database, not
 * whichever one the URL happened to name. The decision is pure so it can be
 * tested without a database.
 */
object SafetyPreflight {
    const val ACK_VARIABLE = "TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK"
    const val ACK_VALUE = "I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE"
    val TENANT_TABLES = listOf("workspace", "inbox", "message")

    fun decide(facts: PreflightFacts): PreflightDecision {
        val reasons = mutableListOf<String>()
        if (facts.acknowledgement != ACK_VALUE) {
            reasons += "$ACK_VARIABLE is not set to exactly '$ACK_VALUE'"
        }
        if (!facts.databaseName.lowercase().contains("bench")) {
            reasons += "the target database name '${facts.databaseName}' does not contain 'bench'"
        }
        val missing = TENANT_TABLES.filter { it !in facts.tenantRowCounts }
        if (missing.isNotEmpty()) {
            reasons += "row counts were not established for: ${missing.joinToString()}"
        }
        val populated = facts.tenantRowCounts.filterValues { it > 0 }
        if (populated.isNotEmpty() && !facts.createdByHarness) {
            reasons += "the database already holds tenant rows (${describe(populated)}); the harness never seeds into one"
        }
        if (populated.isNotEmpty() && facts.createdByHarness) {
            // Impossible unless something else wrote to the database between
            // creation and this check. Refuse rather than assume.
            reasons += "the database was created by this run but already holds tenant rows (${describe(populated)})"
        }
        if (reasons.isNotEmpty()) return PreflightDecision.Refused(reasons)
        return PreflightDecision.Allowed(
            "database '${facts.databaseName}' (${if (facts.createdByHarness) "created by this run" else "pre-existing"}), " +
                "tenant rows at start: ${describe(facts.tenantRowCounts)}; $ACK_VARIABLE acknowledged",
        )
    }

    private fun describe(counts: Map<String, Long>): String = counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
}
