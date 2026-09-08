package email.testinbox.client

import email.testinbox.client.internal.transport.SDK_VERSION
import email.testinbox.client.internal.transport.USER_AGENT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SdkVersionTest {
    @Test
    fun `the advertised version is the one Gradle actually builds`() {
        // The header's purpose is attribution in someone else's access log, so
        // a version that drifts from the published artifact is worse than
        // sending none at all.
        val buildFile = Path.of("build.gradle.kts")
        assertTrue(Files.exists(buildFile), "build.gradle.kts not found; this test would prove nothing")
        val declared =
            Regex("""^version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
                .find(Files.readString(buildFile))
                ?.groupValues
                ?.get(1)
        assertEquals(declared, SDK_VERSION)
    }

    @Test
    fun `the user agent names this SDK, not the runtime`() {
        assertEquals("testinbox-sdk-jvm/$SDK_VERSION", USER_AGENT)
    }
}
