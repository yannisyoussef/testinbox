package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.usecase.ConsumeProviderLoginState
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.PurgeProviderLoginState
import email.testinbox.application.usecase.RecordProviderLoginState
import email.testinbox.application.usecase.SignInWithVerifiedProvider
import email.testinbox.application.usecase.VerifyEmailLogin
import email.testinbox.architecture.fixtures.RogueProviderLoginFixture
import org.junit.jupiter.api.Test

class ProviderLoginWriterRuleTest {
    @Test
    fun `provider state writes remain in their application lifecycle transactions`() {
        val production = production()
        stateWriters(production, "insert", setOf(RecordProviderLoginState::class.java.name))
        stateWriters(production, "replaceForBrowser", setOf(RecordProviderLoginState::class.java.name))
        stateWriters(production, "consume", setOf(ConsumeProviderLoginState::class.java.name))
        stateWriters(production, "purge", setOf(RecordProviderLoginState::class.java.name, PurgeProviderLoginState::class.java.name))
    }

    @Test
    fun `only verified email and provider use cases may invoke human session issuance`() {
        issuers(production())
    }

    @Test
    fun `direct concrete state writes and unverified issuers are independently rejected`() {
        val fixture = RogueProviderLoginFixture::class.java.name
        val violating = ClassFileImporter().importClasses(RogueProviderLoginFixture::class.java)
        val stateFailure =
            runCatching {
                stateWriters(
                    violating,
                    "insert",
                    setOf(RecordProviderLoginState::class.java.name),
                )
            }.exceptionOrNull()
        check(stateFailure is IllegalStateException && fixture in stateFailure.message.orEmpty())
        val issuerFailure = runCatching { issuers(violating) }.exceptionOrNull()
        check(issuerFailure is IllegalStateException && fixture in issuerFailure.message.orEmpty())
    }

    private fun production(): JavaClasses =
        ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox")

    private fun stateWriters(
        classes: JavaClasses,
        method: String,
        permitted: Set<String>,
    ) {
        val actual = callers(classes, ProviderLoginStates::class.java, method)
        check(actual == permitted) { "provider $method writers $actual differ from $permitted" }
    }

    private fun issuers(classes: JavaClasses) {
        val actual = callers(classes, IssueHumanSession::class.java, "execute")
        val permitted = setOf(VerifyEmailLogin::class.java.name, SignInWithVerifiedProvider::class.java.name)
        check(actual == permitted) { "unverified session issuers: $actual; allowed $permitted" }
    }

    private fun callers(
        classes: JavaClasses,
        owner: Class<*>,
        method: String,
    ): Set<String> =
        classes
            .flatMap { it.methods + it.constructors }
            .filter { member ->
                // Kotlin's default-argument bridge delegates within the issuer;
                // it is not an external verifier or an HTTP caller.
                member.owner.name != owner.name &&
                    member.callsFromSelf.any { it.targetOwner.isAssignableTo(owner) && it.name.substringBefore('-') == method }
            }.map { it.owner.name }
            .toSet()
}
