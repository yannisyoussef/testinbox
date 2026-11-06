package email.testinbox.persistence

import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.Collections
import javax.sql.DataSource

/**
 * B-7 (slot-lane escrow design §7, "How the slow path is built"; M1 prototype):
 * under `GLOBAL`, with no escrow strategy, mode a's T1 transaction is BYTE-IDENTICAL
 * to the one the unmodified code sent at 637fea7, statement by statement: every
 * SQL text the driver is asked to prepare or execute, in order, and every
 * transaction call on the connection (`setAutoCommit`, `commit`, `rollback`,
 * isolation), from the admission call's first `getConnection` to its last `close`.
 * `AdmissionStatementTextTest` pins only the snapshot statement's text; this pins
 * the SETs, the lock, the snapshot, the insert, its batched COMMIT and the
 * template's own commit.
 *
 * The fixtures under `src/test/resources/mode-a-sequence/` were recorded from the
 * code at 637fea7, BEFORE any escrow change was made, and are never regenerated
 * from the code under test. A mismatch writes the actual sequence to
 * `build/mode-a-sequence/` for review; it is never copied over the fixture to make
 * the test pass.
 */
class ModeASequenceTest : PartitionedAdmissionFixture() {
    @Test
    fun `GLOBAL under ALL with the footprint read is byte-identical to 637fea7`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        val recorder = SequenceRecorder(db.dataSource)
        val store = JdbcStorageAdmission(recorder.jdbc, recorder.transactions, readsFootprint = true)
        val admission =
            StorageAdmission(store, tenantPolicy, StorageEnforcement.ALL, footprint = EffectiveStoragePolicy.footprint(declarations))
        val result =
            admission.admit(
                fx.request(
                    COPY,
                    listOf(fx.candidate(a, cohort.inboxes.getValue(a)[0], attachments = 1), fx.candidate(b, cohort.inboxes.getValue(b)[1], 1)),
                ),
            )
        result.shape() shouldBe listOf("admitted", "admitted")
        recorder.assertMatches("global-all-footprint.txt")
    }

    @Test
    fun `GLOBAL under OFF without the footprint read is byte-identical to 637fea7`() {
        val ws = db.workspace()
        val inboxes = List(3) { db.inbox(ws) }
        val recorder = SequenceRecorder(db.dataSource)
        val store = JdbcStorageAdmission(recorder.jdbc, recorder.transactions, readsFootprint = false)
        val admission = StorageAdmission(store, tenantPolicy, StorageEnforcement.OFF)
        val result = admission.admit(fx.request(COPY, inboxes.map { fx.candidate(ws, it) }))
        result.shape() shouldBe listOf("admitted", "admitted", "admitted")
        recorder.assertMatches("global-off.txt")
    }

    @Test
    fun `GLOBAL with nothing reserved (every copy refused) is byte-identical to 637fea7`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val recorder = SequenceRecorder(db.dataSource)
        val store = JdbcStorageAdmission(recorder.jdbc, recorder.transactions, readsFootprint = false)
        val tiny = AdmissionFixture.policy(workspace = COPY - 1, share = "1", global = 10 * gib)
        val result = StorageAdmission(store, tiny, StorageEnforcement.TENANT_LIMITS).admit(fx.request(COPY, listOf(fx.candidate(ws, inbox))))
        result.shape() shouldBe listOf("INBOX_LIMIT")
        recorder.assertMatches("global-refused.txt")
    }

    /** Records every SQL text and every transaction call, in order, as one line each. */
    private class SequenceRecorder(
        delegate: DataSource,
    ) {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dataSource: DataSource =
            proxy(delegate, DataSource::class.java) { target, method, args ->
                val result = forward(target, method, args)
                if (method.name == "getConnection") {
                    lines += "getConnection"
                    connection(result as Connection)
                } else {
                    result
                }
            }
        val jdbc: JdbcClient = JdbcClient.create(dataSource)
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))

        private fun connection(target: Connection): Connection =
            proxy(target, Connection::class.java) { t, method, args ->
                when (method.name) {
                    "setAutoCommit", "setTransactionIsolation", "setReadOnly", "setHoldability" -> lines += "${method.name}(${args!![0]})"
                    "commit", "rollback", "close", "releaseSavepoint", "setSavepoint" -> lines += method.name
                    "prepareStatement", "prepareCall", "nativeSQL" -> lines += "${method.name}: ${args!![0]}"
                }
                val result = forward(t, method, args)
                when (result) {
                    is PreparedStatement -> statement(result, PreparedStatement::class.java)
                    is Statement -> statement(result, Statement::class.java)
                    else -> result
                }
            }

        private fun <S : Statement> statement(
            target: S,
            type: Class<S>,
        ): S =
            proxy(target, type) { t, method, args ->
                if (method.name.startsWith("execute") || method.name == "addBatch") {
                    val sql = args?.firstOrNull() as? String
                    lines += if (sql != null) "${method.name}: $sql" else method.name
                }
                forward(t, method, args)
            }

        fun assertMatches(fixture: String) {
            val actual = lines.joinToString("\n", postfix = "\n")
            val expected =
                checkNotNull(javaClass.getResourceAsStream("/mode-a-sequence/$fixture")) {
                    writeActual(fixture, actual)
                    "no fixture /mode-a-sequence/$fixture; the actual sequence was written to build/mode-a-sequence/$fixture"
                }.use { it.readAllBytes().decodeToString() }
            if (actual != expected) writeActual(fixture, actual)
            actual shouldBe expected
        }

        private fun writeActual(
            fixture: String,
            actual: String,
        ) {
            val out = Path.of("build", "mode-a-sequence", fixture)
            Files.createDirectories(out.parent)
            Files.writeString(out, actual)
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun <T : Any> proxy(
                target: T,
                type: Class<T>,
                handler: (T, Method, Array<out Any?>?) -> Any?,
            ): T =
                Proxy.newProxyInstance(
                    ModeASequenceTest::class.java.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, args -> handler(target, method, args) },
                ) as T

            fun forward(
                target: Any,
                method: Method,
                args: Array<out Any?>?,
            ): Any? =
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
        }
    }
}
