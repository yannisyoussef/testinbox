package email.testinbox.e2e

import io.kotest.matchers.shouldBe
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

    private fun run(
        vararg command: String,
        env: Map<String, String> = emptyMap(),
    ): Int {
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
        return process.exitValue()
    }

    @Test
    fun `TypeScript SDK exercises the live stack end-to-end`() {
        val npm = findNpm()
        check(sdkDir.resolve("package.json").isFile) { "sdk/typescript missing at $sdkDir" }
        if (!sdkDir.resolve("node_modules").isDirectory) {
            run(npm, "ci") shouldBe 0
        }
        val exit =
            run(
                npm,
                "run",
                "test:integration",
                env =
                    mapOf(
                        "TESTINBOX_BASE_URL" to E2eStack.apiBaseUrl,
                        "TESTINBOX_API_KEY" to E2eStack.API_KEY,
                    ),
            )
        exit shouldBe 0
    }
}
