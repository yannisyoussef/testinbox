package email.testinbox.persistence

import email.testinbox.application.port.AmbiguityRecord
import email.testinbox.application.port.StorageAmbiguity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
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
 * restarts. That is the finalize budget H.
 */
class JdbcStorageAmbiguity(
    private val jdbc: JdbcClient,
    private val transactions: TransactionOperations,
) : StorageAmbiguity {
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

    override fun due(limit: Int): List<AmbiguityRecord> =
        jdbc
            .sql(
                """
                SELECT id, node_id, object_key, ambiguous_at FROM storage_ambiguity
                 WHERE resolved_at IS NULL AND verify_at <= now()
                 ORDER BY id LIMIT :limit
                """.trimIndent(),
            ).param("limit", limit)
            .query { rs, _ ->
                AmbiguityRecord(
                    rs.getLong("id"),
                    rs.getString("node_id"),
                    rs.getString("object_key"),
                    checkNotNull(Timestamps.fromDb(rs, "ambiguous_at")),
                )
            }.list()

    override fun resolve(id: Long) {
        jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL").param("id", id).update()
    }

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
    ) {
        jdbc
            .sql("UPDATE storage_node SET heartbeat_at = now() WHERE node_id = :node AND generation = :generation")
            .param("node", nodeId)
            .param("generation", generation)
            .update()
    }

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
                        // flight. At most one row per slot, keyless.
                        val (started, largest) =
                            jdbc
                                .sql(
                                    """
                                    SELECT count(*), coalesce(max(bytes), 0) FROM storage_reservation
                                     WHERE node_id = :node AND generation = :generation AND first_upload_at IS NOT NULL
                                    """.trimIndent(),
                                ).param("node", node)
                                .param("generation", generation)
                                .query { rs, _ -> rs.getInt(1) to rs.getLong(2) }
                                .single()
                        repeat(minOf(started, slots)) {
                            record(node, null, largest, verifyAfter)
                            recorded++
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
        jdbc
            .sql("INSERT INTO storage_admission_latch (id, reason, latched_at) VALUES (1, :reason, now()) ON CONFLICT (id) DO NOTHING")
            .param("reason", reason.take(500))
            .update()
    }
}
