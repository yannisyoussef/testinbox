package email.testinbox.persistence

import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.StorageAdmissionScope
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Types
import java.time.Duration

/**
 * The slot-lane escrow strategy [JdbcStorageAdmission] receives by constructor
 * (ADR-038 PROPOSED; structural-admission-design.md §4.4, §7 "How the slow path
 * is built"). M1 PROTOTYPE: only the storage benchmark builds one.
 *
 * Under escrow it supplies, inside T1's own transaction:
 * - [escrowDrainFence] in place of `acquireAdmissionLock`: `(35, 1)`, then every
 *   lane, which waits for every in-flight fast path and excludes new ones;
 * - [escrowLockScopes]: the event's scope rows (rank 6);
 * - [escrowRegrant], strictly AFTER the reservation insert (H1), so the truth it
 *   grants from includes this event's own reservations.
 *
 * **Open for test mutants only** (the H1 mutant regrants before the insert). The
 * architecture suite forbids any main-code subclass from overriding these hooks.
 */
open class EscrowSlowPath(
    private val jdbc: JdbcClient,
    val inputs: EscrowInputs,
    /** Told about each regrant this strategy runs and how long it took (benchmark metrics). */
    private val onRegrant: (EscrowRegrant, Duration) -> Unit = { _, _ -> },
) {
    open fun escrowDrainFence() {
        jdbc.sql(EscrowSql.FENCE).query().singleRow()
    }

    open fun escrowLockScopes(scope: StorageAdmissionScope) {
        jdbc
            .sql(EscrowSql.LOCK_SCOPES)
            .param("ws", EscrowSql.array("uuid", scope.workspaceIds.map { it.value }), Types.ARRAY)
            .param("ib", EscrowSql.array("uuid", scope.inboxIds.map { it.value }), Types.ARRAY)
            .query()
            .singleRow()
    }

    /**
     * The one global regrant ([EscrowSql.REGRANT]) and the event's scope refills.
     * [scope] is null for the keeper's refill-only path. [notReady] is the
     * application's readiness verdict on the snapshot this transaction read.
     */
    open fun escrowRegrant(
        scope: StorageAdmissionScope?,
        notReady: String?,
    ): EscrowRegrant {
        val started = System.nanoTime()
        val regrant =
            jdbc
                .sql(EscrowSql.REGRANT)
                .param("ws", EscrowSql.array("uuid", scope?.workspaceIds?.map { it.value }.orEmpty()), Types.ARRAY)
                .param("ib", EscrowSql.array("uuid", scope?.inboxIds?.map { it.value }.orEmpty()), Types.ARRAY)
                .param("ready", notReady == null)
                .param("reason", notReady, Types.VARCHAR)
                .param("binding", inputs.binding)
                .param("maxStored", inputs.maxStoredBytes)
                .param("share", inputs.inboxShare.toPlainString())
                .param("block", inputs.model.blockSizeBytes)
                .param("den", inputs.model.fragmentationDenominator)
                .param("omax", inputs.model.objectOverheadMaxBytes)
                .query { rs, _ ->
                    EscrowRegrant(
                        lanes = rs.getInt("o_lanes"),
                        payloadGrant = rs.getLong("o_payload_grant"),
                        footprintGrant = rs.getLong("o_footprint_grant"),
                        granted = rs.getBoolean("o_granted"),
                        reason = rs.getString("o_reason"),
                    )
                }.single()
        onRegrant(regrant, Duration.ofNanos(System.nanoTime() - started))
        return regrant
    }
}
