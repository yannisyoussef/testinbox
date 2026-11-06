package email.testinbox.benchmark

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The rows production's footprint reads need before T1 can decide under `ALL`
 * (filesystem-containment contract §4.5, §5.3): a trusted ledger, a monitor
 * observation, and the debt watermark. Seeded so T1's footprint reads take
 * the real path, never the `451` short-cut of an unready deployment.
 *
 * - **Trust** is marked the way only the schema owner can mark it, through
 *   the V10 verifier's own transaction-local marker. The harness's standing
 *   ledger is synthetic (see [Seeder]), so `storage_confirm_footprint_trust()`
 *   would find drift and refuse; the admission cost does not depend on how the
 *   mark was made. The harness connects as the owner of its own benchmark
 *   database (the preflight proves it is the harness's), never of a real one.
 * - **The observation** is written through `storage_begin_observation()` as
 *   the harness's login role, which [HarnessDeclarations] declares as the
 *   monitor role, with the declared block size and capacity and no trash.
 * - **The watermark** row exists from V8; it is (re)stated at 0 if missing.
 *
 * Idempotent and cheap, so it runs before every scenario: a population wipe
 * deletes deltas through V8's folding trigger, which revokes trust.
 */
object FootprintSeed {
    fun monitorRole(jdbc: JdbcClient): String =
        jdbc
            .sql("SELECT session_user::text")
            .query(String::class.java)
            .single()

    fun seed(jdbc: JdbcClient) {
        jdbc.sql("INSERT INTO storage_debt_watermark (id, compacted_through_seq) VALUES (1, 0) ON CONFLICT (id) DO NOTHING").update()
        jdbc
            .sql(
                """
                DO ${'$'}seed${'$'}
                BEGIN
                    INSERT INTO storage_footprint_trust (id, distrust_epoch) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;
                    PERFORM set_config('testinbox.trust_verifier', 'v10', true);
                    UPDATE storage_footprint_trust
                       SET trusted_epoch = distrust_epoch, trusted_seq = nextval('storage_debt_order_seq'), trusted_at = clock_timestamp()
                     WHERE id = 1 AND trusted_epoch IS DISTINCT FROM distrust_epoch;
                    PERFORM set_config('testinbox.trust_verifier', '', true);
                END
                ${'$'}seed${'$'}
                """.trimIndent(),
            ).update()
        val walk =
            jdbc
                .sql("SELECT storage_begin_observation()")
                .query(Long::class.java)
                .single()
        jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total, inodes_used,
                     trash_bytes, minio_sys_bytes)
                VALUES (:walk, 'storage-benchmark', :block, :capacity, 0, :capacity, :inodes, 0, 0, 0)
                """.trimIndent(),
            ).param("walk", walk)
            .param("block", HarnessDeclarations.BLOCK_SIZE_BYTES)
            .param("capacity", HarnessDeclarations.CAPACITY_BYTES)
            .param("inodes", HarnessDeclarations.INODES)
            .update()
    }
}
