package email.testinbox.e2e

import email.testinbox.client.TestInboxClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the TypeScript SDK's live integration suite against the running
 * walking-skeleton stack. Node >= 20 must be available (CI installs it);
 * a missing runtime FAILS the test rather than silently skipping.
 */
class TsSdkIntegrationTest {
    private val sdkDir = File("../../sdk/typescript").canonicalFile

    private fun findNpm(): String {
        val fromPath =
            System
                .getenv("PATH")
                ?.split(File.pathSeparator)
                ?.map { File(it, "npm") }
                ?.firstOrNull { it.canExecute() }
        if (fromPath != null) return fromPath.absolutePath
        val homebrewNode = File("/opt/homebrew/opt/node@22/bin/npm")
        check(homebrewNode.canExecute()) { "npm not found on PATH — Node >= 20 is required for e2e" }
        return homebrewNode.absolutePath
    }

    private class Run(
        val exit: Int,
        val output: String,
    )

    private fun run(
        vararg command: String,
        env: Map<String, String> = emptyMap(),
    ): Run {
        val process =
            ProcessBuilder(*command)
                .directory(sdkDir)
                .redirectErrorStream(true)
                .also { it.environment().putAll(env) }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "command timed out: ${command.joinToString(" ")}" }
        if (process.exitValue() != 0) {
            System.err.println(output)
        }
        return Run(process.exitValue(), output)
    }

    /**
     * The TS process cannot write the database, so a refusal that must land
     * AFTER the TS test holds an `Inbox` object is arranged by a file
     * handshake: the test writes the inbox id to [request], this side records
     * the §6a refusal, then touches [done]. State-based, no sleeps decide it.
     */
    private fun refuseOnRequest(
        request: File,
        done: File,
    ): Thread =
        Thread {
            val until = System.nanoTime() + 120_000_000_000L
            while (!request.isFile && System.nanoTime() < until) Thread.sleep(50)
            if (request.isFile) {
                E2eStorage.recordRefusal(request.readText().trim())
                done.writeText("refused\n")
            }
        }.apply {
            isDaemon = true
            start()
        }

    @Test
    fun `TypeScript SDK exercises the live stack end-to-end`() {
        val npm = findNpm()
        check(sdkDir.resolve("package.json").isFile) { "sdk/typescript missing at $sdkDir" }
        if (!sdkDir.resolve("node_modules").isDirectory) {
            run(npm, "ci").exit shouldBe 0
        }
        val handshake =
            java.nio.file.Files
                .createTempDirectory("ti-storage-005-handshake")
                .toFile()
        val request = handshake.resolve("refuse-request")
        val done = handshake.resolve("refuse-done")
        val refuser = refuseOnRequest(request, done)
        // An inbox that already carries a refusal when the TS suite fetches it.
        val preRefused = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl).createInboxBlocking()
        E2eStorage.recordRefusal(preRefused.id)
        val result =
            run(
                npm,
                "run",
                "test:integration",
                env =
                    mapOf(
                        "TESTINBOX_BASE_URL" to E2eStack.apiBaseUrl,
                        "TESTINBOX_API_KEY" to E2eStack.API_KEY,
                        "TESTINBOX_REFUSED_INBOX_ID" to preRefused.id,
                        "TESTINBOX_REFUSAL_REQUEST_FILE" to request.absolutePath,
                        "TESTINBOX_REFUSAL_DONE_FILE" to done.absolutePath,
                    ),
            )
        refuser.join(5_000)
        result.exit shouldBe 0
        // All four live cases ran: a skipped case (for instance a lost
        // environment variable) would still exit 0 and prove nothing.
        result.output shouldContain "4 passed"
        result.output.contains("skipped") shouldBe false
    }
}
