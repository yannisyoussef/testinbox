package email.testinbox.persistence

import email.testinbox.application.port.ClaimOutcome
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.IdempotentOperation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-033 §2 is a claim about PostgreSQL, so it is tested against PostgreSQL.
 *
 * The design removed `IN_PROGRESS` state, leases and takeover logic on the
 * strength of "a duplicate blocks on the unique index until the first
 * transaction resolves". If that is not true, none of the rest is.
 */
class JdbcIdempotencyRecordsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var records: JdbcIdempotencyRecords

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var tx: TransactionRunner

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun scope(): IdempotencyScope {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        return IdempotencyScope(workspaceId, projectId, IdempotentOperation.CREATE_INBOX)
    }

    private fun claim(
        scope: IdempotencyScope,
        keyHash: String,
        fingerprint: String = "fp",
        waitFor: Duration = Duration.ofSeconds(2),
    ): ClaimOutcome =
        records.claim(
            scope = scope,
            keyHash = keyHash,
            fingerprint = fingerprint,
            claimedByApiKeyId = null,
            now = now,
            expiresAt = now.plusSeconds(3600),
            waitFor = waitFor,
        )

    @Test
    fun `the first claim wins and a later identical one replays what it stored`() {
        val scope = scope()
        tx.required {
            claim(scope, "k1") shouldBe ClaimOutcome.Claimed
            records.complete(scope, "k1", IdempotencySnapshot(1, mapOf("inboxId" to "abc")))
        }
        val replay = tx.required { claim(scope, "k1") }.shouldBeInstanceOf<ClaimOutcome.Replay>()
        replay.snapshot.version shouldBe 1
        replay.snapshot.payload["inboxId"] shouldBe "abc"
    }

    @Test
    fun `the same key with a different fingerprint is a conflict, not a second execution`() {
        val scope = scope()
        tx.required {
            claim(scope, "k1", fingerprint = "first")
            records.complete(scope, "k1", IdempotencySnapshot(1, mapOf("inboxId" to "abc")))
        }
        tx.required { claim(scope, "k1", fingerprint = "second") } shouldBe ClaimOutcome.FingerprintMismatch
    }

    @Test
    fun `a rolled back claim leaves the key free, so a rejection never binds it`() {
        val scope = scope()
        // ADR-033 §4: CreateInbox returns quota and validation refusals as
        // values, so the transaction would otherwise commit and freeze a
        // transient failure into the key for the whole retention window.
        runCatching {
            tx.required {
                claim(scope, "k1") shouldBe ClaimOutcome.Claimed
                error("the mutation was rejected")
            }
        }
        tx.required { claim(scope, "k1") } shouldBe ClaimOutcome.Claimed
    }

    @Test
    fun `a concurrent duplicate blocks on the claim and then replays — no IN_PROGRESS state needed`() {
        val scope = scope()
        val firstHasClaimed = CountDownLatch(1)
        val secondIsBlocked = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit {
                    tx.required {
                        claim(scope, "k1") shouldBe ClaimOutcome.Claimed
                        firstHasClaimed.countDown()
                        // Hold the transaction open until the duplicate is
                        // provably blocked on this row, then commit.
                        secondIsBlocked.await(15, TimeUnit.SECONDS)
                        records.complete(scope, "k1", IdempotencySnapshot(1, mapOf("inboxId" to "winner")))
                    }
                }
            firstHasClaimed.await(5, TimeUnit.SECONDS) shouldBe true

            val second =
                pool.submit<ClaimOutcome> {
                    tx.required { claim(scope, "k1", waitFor = Duration.ofSeconds(10)) }
                }

            // Asked of Postgres rather than assumed after a sleep. A latch
            // counted down *before* the claim call (the earlier shape) proves
            // only that the thread started, and a `Thread.sleep` widens the
            // race rather than removing it: if the second thread were slow to
            // schedule, the test would quietly degenerate into the sequential
            // replay already covered above and still pass, leaving the headline
            // claim — that it *waited on the index* — unproven.
            await().atMost(Duration.ofSeconds(10)).until {
                jdbc
                    .sql("SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0")
                    .query(Int::class.java)
                    .single() > 0
            }
            secondIsBlocked.countDown()
            first.get(15, TimeUnit.SECONDS)

            // It did not execute, and it did not see a half-written record: it
            // waited on the index and then read the committed result.
            val replay = second.get(15, TimeUnit.SECONDS).shouldBeInstanceOf<ClaimOutcome.Replay>()
            replay.snapshot.payload["inboxId"] shouldBe "winner"
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `N simultaneous claims produce exactly one execution`() {
        val scope = scope()
        val racers = 8
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(racers)
        try {
            val outcomes =
                (1..racers)
                    .map {
                        pool.submit<ClaimOutcome> {
                            gate.await()
                            tx.required {
                                val outcome = claim(scope, "k1", waitFor = Duration.ofSeconds(10))
                                if (outcome == ClaimOutcome.Claimed) {
                                    records.complete(scope, "k1", IdempotencySnapshot(1, mapOf("inboxId" to "one")))
                                }
                                outcome
                            }
                        }
                    }.also { gate.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }

            outcomes.count { it == ClaimOutcome.Claimed } shouldBe 1
            outcomes.count { it is ClaimOutcome.Replay } shouldBe racers - 1
            // Scoped to this workspace: other cases in this class deliberately
            // reuse the key value elsewhere, which is the point of the scope.
            jdbc
                .sql(
                    "SELECT count(*) FROM idempotency_record " +
                        "WHERE workspace_id = :ws AND operation = :op AND key_hash = 'k1'",
                ).param("ws", scope.workspaceId.value)
                .param("op", scope.operation.wire)
                .query(Int::class.java)
                .single() shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a claim held open past the wait is reported as contended, never as an error`() {
        val scope = scope()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                tx.required {
                    claim(scope, "k1")
                    held.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            held.await(5, TimeUnit.SECONDS) shouldBe true

            // No `runCatching` here, deliberately. Swallowing the exception
            // and substituting `Contended` would make this test pass if the
            // claim threw, if committing the poisoned transaction threw, or if
            // the pool refused the connection — i.e. it would assert nothing
            // about the one property it is named for. ADR-033 §3 claims the
            // lock timeout is rendered as a value rather than an error, and
            // that Postgres turns a COMMIT on an aborted transaction into a
            // silent ROLLBACK; this is the only test that would notice either
            // being false, so it must be able to fail.
            val contended =
                pool
                    .submit<ClaimOutcome> {
                        tx.required { claim(scope, "k1", waitFor = Duration.ofMillis(250)) }
                    }.get(15, TimeUnit.SECONDS)
            // Transient, and distinguishable from a fingerprint conflict: the
            // correct client action for one is retry and for the other is never.
            contended shouldBe ClaimOutcome.Contended
            release.countDown()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the claim timeout does not leak onto the rest of the transaction`() {
        // The bug this pins: `lock_timeout` is transaction-scoped, so left in
        // force it would also bound ADR-021's reservation insert and ADR-027's
        // advisory admission lock — aborting correct requests and reporting
        // them as idempotency failures.
        val scope = scope()
        tx.required {
            claim(scope, "k1", waitFor = Duration.ofMillis(50)) shouldBe ClaimOutcome.Claimed
            jdbc.sql("SHOW lock_timeout").query(String::class.java).single() shouldBe "0"
            // And a real lock wait afterwards is unbounded again.
            jdbc.sql("SELECT pg_advisory_xact_lock(4242)").query(String::class.java).optional()
        }
    }

    @Test
    fun `two workspaces may use the same key value independently`() {
        val mine = scope()
        val theirs = scope()
        tx.required {
            claim(mine, "shared") shouldBe ClaimOutcome.Claimed
            records.complete(mine, "shared", IdempotencySnapshot(1, mapOf("inboxId" to "mine")))
        }
        tx.required { claim(theirs, "shared") } shouldBe ClaimOutcome.Claimed
    }

    @Test
    fun `the same key on a different operation is an independent record`() {
        val inbox = scope()
        val keys = inbox.copy(operation = IdempotentOperation.CREATE_API_KEY)
        tx.required {
            claim(inbox, "shared") shouldBe ClaimOutcome.Claimed
            records.complete(inbox, "shared", IdempotencySnapshot(1, emptyMap()))
        }
        tx.required { claim(keys, "shared") } shouldBe ClaimOutcome.Claimed
    }

    @Test
    fun `the sweep removes expired records in bounded batches and spares live ones`() {
        val scope = scope()
        (1..5).forEach { i ->
            tx.required {
                records.claim(
                    scope = scope,
                    keyHash = "expired-$i",
                    fingerprint = "fp",
                    claimedByApiKeyId = null,
                    now = now.minusSeconds(7200),
                    expiresAt = now.minusSeconds(3600),
                    waitFor = Duration.ofSeconds(2),
                )
            }
        }
        tx.required { claim(scope, "live") }

        records.deleteExpired(now, batchSize = 2) shouldBe 2
        records.deleteExpired(now, batchSize = 10) shouldBe 3
        records.deleteExpired(now, batchSize = 10) shouldBe 0
        jdbc
            .sql("SELECT count(*) FROM idempotency_record WHERE key_hash = 'live'")
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `the sweep skips a row a live claim holds, rather than blocking behind it`() {
        // ADR-033 §9. `claim` takes a row lock deliberately (`ON CONFLICT DO
        // UPDATE`), so the sweep and a claim genuinely contend. Without
        // `SKIP LOCKED` the sweep blocks on the holder; worse, a claim landing
        // on a row the sweep already locked exhausts its `lock_timeout` and is
        // reported to the client as `idempotency-request-in-progress` when no
        // request is in progress.
        val scope = scope()
        // Committed and already expired: exactly what the sweep targets.
        tx.required {
            records.claim(
                scope = scope,
                keyHash = "contended",
                fingerprint = "fp",
                claimedByApiKeyId = null,
                now = now.minusSeconds(7200),
                expiresAt = now.minusSeconds(3600),
                waitFor = Duration.ofSeconds(2),
            )
        }

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val sweeper = Executors.newSingleThreadExecutor()
        try {
            holder.submit {
                tx.required {
                    claim(scope, "contended")
                    holding.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            holding.await(10, TimeUnit.SECONDS) shouldBe true

            // The assertion is that this *returns at all* while the lock is
            // held. It skips the one row it could have taken, so nothing is
            // removed and the next tick picks it up — the record is expired,
            // so nothing about deleting it is urgent.
            val removed = sweeper.submit<Int> { records.deleteExpired(now, batchSize = 10) }
            removed.get(5, TimeUnit.SECONDS) shouldBe 0
        } finally {
            release.countDown()
            holder.shutdown()
            sweeper.shutdown()
        }

        // And once the holder is gone the row is collectable as normal, so the
        // skip defers the delete rather than losing it.
        holder.awaitTermination(10, TimeUnit.SECONDS)
        records.deleteExpired(now, batchSize = 10) shouldBe 1
    }
}
