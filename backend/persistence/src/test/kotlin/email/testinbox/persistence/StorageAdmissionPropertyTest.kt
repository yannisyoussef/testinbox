package email.testinbox.persistence

import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.random.Random

/**
 * ADR-035 T1 as a property: for fixed-seed random accounting states,
 * reservation backlogs, policies, envelope orders and enforcement modes, the
 * PostgreSQL adapter decides exactly what a small, independent reference
 * model decides. That covers the admitted set, the refusal reasons, the
 * order, and the reservation bytes.
 *
 * The model shares no code with `StorageAdmissionRules`: it is a plain loop
 * over its own byte tallies, which it keeps from the seeding operations
 * rather than from the database. A failure names its seed and event, and
 * prints the model's full state.
 */
class StorageAdmissionPropertyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    /** The reference model: byte tallies per scope, and the decision loop. */
    private class Model {
        val workspace = mutableMapOf<UUID, Long>()
        val inbox = mutableMapOf<UUID, Long>()
        var global = 0L

        fun add(
            ws: UUID,
            ib: UUID?,
            bytes: Long,
        ) {
            workspace.merge(ws, bytes, Long::plus)
            if (ib != null) inbox.merge(ib, bytes, Long::plus)
            global += bytes
        }

        /** Tenant ceilings enforced unless OFF; the global cap only under ALL. */
        fun decide(
            inboxLimit: Long,
            workspaceLimit: Long,
            cap: Long,
            enforcement: StorageEnforcement,
            f: Long,
            candidates: List<Pair<UUID, UUID>>,
        ): List<String> =
            candidates.map { (ws, ib) ->
                val tenant = enforcement != StorageEnforcement.OFF
                val enforceGlobal = enforcement == StorageEnforcement.ALL
                val reason =
                    when {
                        tenant && (inbox[ib] ?: 0) + f > inboxLimit -> "INBOX_LIMIT"
                        tenant && (workspace[ws] ?: 0) + f > workspaceLimit -> "WORKSPACE_LIMIT"
                        enforceGlobal && global + f > cap -> "SERVICE_CAPACITY"
                        else -> null
                    }
                if (reason == null) {
                    add(ws, ib, f)
                    "admitted"
                } else {
                    reason
                }
            }

        override fun toString() = "global=$global workspaces=$workspace inboxes=$inbox"
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 35_035])
    fun `the adapter decides exactly what the reference model decides`(seed: Long) {
        val random = Random(seed)
        val fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        val db = fx.db
        val model = Model()
        val tally = sortedMapOf<String, Int>()

        // Accounting state: 1–4 workspaces of 1–3 inboxes, every sum represented.
        val workspaces = List(random.nextInt(1, 5)) { db.workspace() }
        val inboxes = workspaces.associateWith { ws -> List(random.nextInt(1, 4)) { db.inbox(ws) } }
        for ((ws, ibs) in inboxes) {
            for (ib in ibs) {
                if (random.nextBoolean()) {
                    random.nextLong(0, 60).let {
                        fx.base(ws, ib, it)
                        model.add(ws, ib, it)
                    }
                }
                if (random.nextBoolean()) {
                    random.nextLong(1, 30).let {
                        fx.delta(ws, ib, it)
                        model.add(ws, ib, it)
                    }
                }
                repeat(random.nextInt(0, 3)) {
                    val bytes = random.nextLong(1, 25)
                    fx.reservation(ws, ib, bytes, state = if (random.nextBoolean()) "RESERVED" else "RELEASING")
                    model.add(ws, ib, bytes)
                }
            }
            // A cascaded delete's delta carries no inbox (ADR-035 §10), and can be negative.
            if (random.nextInt(4) == 0) {
                random.nextLong(-50, 50).let {
                    fx.delta(ws, null, it)
                    model.add(ws, null, it)
                }
            }
        }
        val shareHundredths = listOf(37L, 40L, 63L, 80L, 100L).random(random)
        val share = if (shareHundredths == 100L) "1" else "0.$shareHundredths"
        val workspaceLimit = random.nextLong(100, 400)
        val g = random.nextLong(400, 2_500)
        val h = random.nextLong(0, g / 4)
        val policy = policy(workspace = workspaceLimit, share = share, global = g, h = h)

        repeat(EVENTS) { event ->
            val enforcement =
                listOf(
                    StorageEnforcement.OFF,
                    StorageEnforcement.TENANT_LIMITS,
                    StorageEnforcement.ALL,
                    StorageEnforcement.ALL,
                ).random(random)
            val f = random.nextLong(1, 40)
            // Distinct inboxes: an event carries at most one copy per inbox.
            val all = inboxes.flatMap { (ws, ibs) -> ibs.map { ws to it } }
            val targets = all.shuffled(random).take(random.nextInt(1, all.size + 1))
            val clue =
                "seed $seed, event $event: f=$f, $enforcement, $policy, candidates=$targets, before: $model"

            val expected =
                model.decide(
                    // The model's own floor, from the share as a fraction of 100, not InboxShare.floorOf.
                    workspaceLimit * shareHundredths / 100,
                    workspaceLimit,
                    g - h,
                    enforcement,
                    f,
                    targets,
                )
            expected.forEach { tally.merge("$enforcement:$it", 1, Int::plus) }
            val reservationsBefore = fx.reservedBytes()
            val candidates = targets.map { (ws, ib) -> fx.candidate(ws, ib, attachments = random.nextInt(0, 3)) }
            val sameShape = candidates.map { it.copy(objectKeys = it.objectKeys.take(1)) } // one parse: one key shape per event
            val result = fx.admission(policy, enforcement).admit(fx.request(f, sameShape))

            withClue(clue) {
                result.decisions.map { it.candidate } shouldBe sameShape // the order is the envelope's
                result.decisions.map {
                    when (it) {
                        is StorageAdmissionDecision.Admitted -> "admitted"
                        is StorageAdmissionDecision.Refused -> it.reason.name
                    }
                } shouldBe expected
                fx.reservedBytes() - reservationsBefore shouldBe expected.count { it == "admitted" } * f
                result.admitted.forEach { admitted ->
                    db.jdbc
                        .sql("SELECT bytes FROM storage_reservation WHERE message_id = ?")
                        .param(admitted.candidate.messageId.value)
                        .query(Long::class.java)
                        .single() shouldBe f
                }
            }
        }
        println("ADR-035 admission property seed $seed: $tally")
    }

    private companion object {
        const val EVENTS = 6
    }
}
