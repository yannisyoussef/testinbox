package email.testinbox.persistence

import email.testinbox.application.port.AmbiguityRecord
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageLatch
import email.testinbox.application.port.coverageNode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * Persisted ambiguity, node generations and the admission latch (ADR-035 §9).
 *
 * An ambiguous upload is a row here before its write slot can be reused. A
 * process that dies leaves its started uploads as keyless rows for its node,
 * recorded by whoever notices first: the same node at restart, or cleanup
 * when the heartbeat goes stale. Either way, per node, in-flight uploads plus
 * unresolved rows never exceed the declared slots, across breaker cycles and
 * restarts. That is the finalize budget H. Every key those uploads covered
 * also gets a keyed coverage row under [coverageNode], which holds no slot and
 * is proved per key at `verify_at`.
 */
class JdbcStorageAmbiguity(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : StorageAmbiguity,
    StorageLatch {
    /**
     * The latch commits on its own, whatever transaction the caller is in: a
     * late object's evidence is deleted right after, and a later rollback of
     * the caller's work must never take the latch with it.
     */
    private val independent =
        TransactionTemplate(checkNotNull(transactions.transactionManager)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun record(
        nodeId: String,
        objectKey: String?,
        bytes: Long,
        verifyAfter: Duration,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO storage_ambiguity (node_id, object_key, bytes, ambiguous_at, verify_at)
                VALUES (:node, :key, :bytes, now(), now() + make_interval(secs => :verify))
                """.trimIndent(),
            ).param("node", nodeId)
            .param("key", objectKey)
            .param("bytes", bytes)
            .param("verify", verifyAfter.seconds.toDouble())
            .update()
    }

    override fun unresolvedFor(nodeId: String): Int =
        jdbc
            .sql("SELECT count(*) FROM storage_ambiguity WHERE node_id = :node AND resolved_at IS NULL")
            .param("node", nodeId)
            .query(Int::class.java)
            .single()

    override fun unresolvedTotal(): Int =
        jdbc.sql("SELECT count(*) FROM storage_ambiguity WHERE resolved_at IS NULL").query(Int::class.java).single()

    override fun holdRefused(id: Long) {
        // Retried after a pause, not at every pass: held rows never crowd out the rows behind them.
        jdbc
            .sql(
                "UPDATE storage_ambiguity SET held_by_rule_p = true, verify_at = now() + make_interval(secs => :retry) " +
                    "WHERE id = :id AND resolved_at IS NULL",
            ).param("id", id)
            .param("retry", HELD_RETRY.toSeconds().toDouble())
            .update()
    }

    override fun holdsCoverage(nodeId: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM storage_ambiguity WHERE node_id = :coverage AND resolved_at IS NULL AND held_by_rule_p)")
            .param("coverage", coverageNode(nodeId))
            .query(Boolean::class.java)
            .single()

    override fun holdLateObject(
        key: String,
        bytes: Long,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO storage_ambiguity (node_id, object_key, bytes, ambiguous_at, verify_at, held_by_rule_p)
                SELECT :node, :key, :bytes, now(), now() + make_interval(secs => :retry), true
                 WHERE NOT EXISTS (SELECT 1 FROM storage_ambiguity WHERE object_key = :key AND resolved_at IS NULL)
                """.trimIndent(),
            ).param("node", HELD_NODE)
            .param("key", key)
            .param("bytes", bytes)
            .param("retry", HELD_RETRY.toSeconds().toDouble())
            .update()
    }

    override fun unresolvedOrphaned(staleHeartbeat: java.time.Duration): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM storage_ambiguity a
                 WHERE a.resolved_at IS NULL
                   AND (a.node_id NOT LIKE 'recovered:%' OR a.held_by_rule_p)
                   AND NOT EXISTS (SELECT 1 FROM storage_node n
                                    WHERE n.node_id = a.node_id AND NOT n.clean_shutdown
                                      AND n.heartbeat_at > now() - make_interval(secs => :stale))
                """.trimIndent(),
            ).param("stale", staleHeartbeat.toSeconds().toDouble())
            .query(Int::class.java)
            .single()

    override fun heldRefused(): Int =
        jdbc
            .sql("SELECT count(*) FROM storage_ambiguity WHERE resolved_at IS NULL AND held_by_rule_p")
            .query(Int::class.java)
            .single()

    override fun due(limit: Int): List<AmbiguityRecord> =
        jdbc
            .sql(
                """
                SELECT id, node_id, object_key, ambiguous_at, held_by_rule_p FROM storage_ambiguity
                 WHERE resolved_at IS NULL AND verify_at <= now()
                 ORDER BY held_by_rule_p, id LIMIT :limit
                """.trimIndent(),
            ).param("limit", limit)
            .query { rs, _ ->
                AmbiguityRecord(
                    rs.getLong("id"),
                    rs.getString("node_id"),
                    rs.getString("object_key"),
                    checkNotNull(Timestamps.fromDb(rs, "ambiguous_at")),
                    rs.getBoolean("held_by_rule_p"),
                )
            }.list()

    override fun resolve(id: Long) {
        jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL").param("id", id).update()
    }

    override fun resolveCommitted(id: Long) {
        jdbc.sql("DELETE FROM storage_ambiguity WHERE id = :id AND resolved_at IS NULL").param("id", id).update()
    }

    override fun wasAmbiguous(
        key: String,
        within: Duration,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (SELECT 1 FROM storage_ambiguity
                                WHERE object_key = :key
                                  AND (resolved_at IS NULL OR resolved_at > now() - make_interval(secs => :within)))
                """.trimIndent(),
            ).param("key", key)
            .param("within", within.seconds.toDouble())
            .query(Boolean::class.java)
            .single()

    override fun oldestUnresolvedAge(): Duration? =
        jdbc
            .sql("SELECT extract(epoch FROM now() - min(ambiguous_at)) FROM storage_ambiguity WHERE resolved_at IS NULL")
            .query(java.math.BigDecimal::class.java)
            .optional()
            .map { Duration.ofMillis((it.toDouble() * 1000).toLong()) }
            .orElse(null)

    override fun registerGeneration(
        nodeId: String,
        generation: UUID,
        capability: String,
    ) {
        jdbc
            .sql(
                "INSERT INTO storage_node (node_id, generation, capability, heartbeat_at, clean_shutdown) " +
                    "VALUES (:node, :generation, :capability, now(), false)",
            ).param("node", nodeId)
            .param("generation", generation)
            .param("capability", capability)
            .update()
    }

    override fun heartbeat(
        nodeId: String,
        generation: UUID,
        capability: String,
    ): Boolean =
        // An upsert: if cleanup declared this generation dead (a stale
        // heartbeat while it was alive), the row comes back, so a later real
        // crash is still recovered. xmax = 0 identifies a fresh insert.
        jdbc
            .sql(
                """
                INSERT INTO storage_node (node_id, generation, capability, heartbeat_at, clean_shutdown)
                VALUES (:node, :generation, :capability, now(), false)
                ON CONFLICT (node_id, generation) DO UPDATE SET heartbeat_at = now()
                RETURNING xmax <> 0
                """.trimIndent(),
            ).param("node", nodeId)
            .param("generation", generation)
            .param("capability", capability)
            .query(Boolean::class.java)
            .single()

    override fun markCleanShutdown(
        nodeId: String,
        generation: UUID,
    ) {
        jdbc
            .sql("UPDATE storage_node SET clean_shutdown = true, heartbeat_at = now() WHERE node_id = :node AND generation = :generation")
            .param("node", nodeId)
            .param("generation", generation)
            .update()
    }

    override fun recoverDeadGenerations(
        nodeId: String?,
        current: UUID?,
        staleAfter: Duration,
        slots: Int,
        verifyAfter: Duration,
    ): Int =
        checkNotNull(
            transactions.execute {
                // Own node at restart: every other generation that did not shut
                // down cleanly, however recent its heartbeat. Any node, from
                // cleanup: generations whose heartbeat went stale.
                val dead =
                    jdbc
                        .sql(
                            """
                            SELECT node_id, generation, clean_shutdown FROM storage_node
                             WHERE (CAST(:node AS text) IS NOT NULL AND node_id = :node AND generation <> :current)
                                OR (CAST(:node AS text) IS NULL AND NOT clean_shutdown
                                    AND heartbeat_at < now() - make_interval(secs => :stale))
                             ORDER BY node_id, generation
                               FOR UPDATE SKIP LOCKED
                            """.trimIndent(),
                        ).param("node", nodeId)
                        .param("current", current ?: UUID(0, 0))
                        .param("stale", staleAfter.seconds.toDouble())
                        .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getBoolean(3)) }
                        .list()
                var recorded = 0
                for ((node, generation, clean) in dead) {
                    if (!clean) {
                        // Started but unconsumed: these might have objects in
                        // flight. An upload already recorded as keyed ambiguity
                        // is not "in flight": it holds its slot through that
                        // row, and must not hold two.
                        val started =
                            jdbc
                                .sql(
                                    """
                                    SELECT r.bytes, r.object_keys FROM storage_reservation r
                                     WHERE r.node_id = :node AND r.generation = :generation AND r.first_upload_at IS NOT NULL
                                       AND NOT EXISTS (SELECT 1 FROM storage_ambiguity a
                                                        WHERE a.resolved_at IS NULL AND a.object_key = ANY (r.object_keys))
                                    """.trimIndent(),
                                ).param("node", node)
                                .param("generation", generation)
                                .query { rs, _ -> rs.getLong(1) to (rs.getArray(2).array as Array<*>).map { it as String } }
                                .list()
                        // At most one keyless row per slot: they bound the node's slots (H).
                        val largest = started.maxOfOrNull { it.first } ?: 0
                        repeat(minOf(started.size, slots)) {
                            record(node, null, largest, verifyAfter)
                            recorded++
                        }
                        // And every key they covered gets its own per-key proof at
                        // verify_at, under a coverage node that holds no slot.
                        for (key in started.flatMap { it.second }.distinct()) {
                            record(coverageNode(node), key, 0, verifyAfter)
                        }
                    }
                    jdbc
                        .sql("DELETE FROM storage_node WHERE node_id = :node AND generation = :generation")
                        .param("node", node)
                        .param("generation", generation)
                        .update()
                }
                recorded
            },
        )

    override fun latched(): String? =
        jdbc
            .sql("SELECT reason FROM storage_admission_latch WHERE id = 1")
            .query(String::class.java)
            .optional()
            .orElse(null)

    override fun latch(reason: String) {
        independent.executeWithoutResult {
            jdbc
                .sql("INSERT INTO storage_admission_latch (id, reason, latched_at) VALUES (1, :reason, now()) ON CONFLICT (id) DO NOTHING")
                .param("reason", reason.take(500))
                .update()
        }
    }

    companion object {
        /** A held late object is re-offered to (P) this long after each refusal. */
        val HELD_RETRY: java.time.Duration = java.time.Duration.ofMinutes(5)

        /** The node id of late objects the orphan sweep holds: never a live node, so they count against every slot. */
        const val HELD_NODE = "held:orphan-sweep"
    }
}
