package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.usecase.PurgeEmailLoginState
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.application.usecase.ResolveVerifiedIdentity
import email.testinbox.application.usecase.VerifyEmailLogin
import email.testinbox.architecture.fixtures.RogueEmailLoginFixture
import org.junit.jupiter.api.Test

class EmailLoginWriterRuleTest {
    @Test
    fun `identity and challenge writes cannot bypass the application transactions`() {
        assertWriters(ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox"))
    }

    @Test
    fun `direct concrete adapter writes fail the identity and challenge rule`() {
        val violation = ClassFileImporter().importClasses(RogueEmailLoginFixture::class.java)
        val fixture = RogueEmailLoginFixture::class.java.name
        val identityFailure =
            runCatching {
                assertOnly(violation, HumanIdentities::class.java, "create", setOf(ResolveVerifiedIdentity::class.java.name))
            }.exceptionOrNull()
        check(identityFailure is IllegalStateException && fixture in identityFailure.message.orEmpty())
        val challengeFailure =
            runCatching {
                assertOnly(violation, EmailLogins::class.java, "insert", setOf(RequestEmailLogin::class.java.name))
            }.exceptionOrNull()
        check(challengeFailure is IllegalStateException && fixture in challengeFailure.message.orEmpty())
    }

    private fun assertWriters(classes: JavaClasses) {
        assertOnly(classes, HumanIdentities::class.java, "create", setOf(ResolveVerifiedIdentity::class.java.name))
        val requesting = setOf(RequestEmailLogin::class.java.name)
        val verifying = setOf(VerifyEmailLogin::class.java.name)
        mapOf(
            "insert" to requesting,
            "replaceForBrowser" to requesting,
            "finishDelivery" to requesting,
            "consume" to verifying,
            "failedAttempt" to verifying,
            "purge" to requesting + PurgeEmailLoginState::class.java.name,
        ).forEach { (method, permitted) ->
            assertOnly(classes, EmailLogins::class.java, method, permitted)
        }
    }

    private fun assertOnly(
        classes: JavaClasses,
        port: Class<*>,
        method: String,
        permitted: Set<String>,
    ) {
        val writers =
            classes
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { it.targetOwner.isAssignableTo(port) && it.name.substringBefore('-') == method }
                }.map { it.owner.name }
                .toSet()
        check(writers == permitted) { "${port.simpleName}.$method writers $writers differ from $permitted" }
    }
}
