package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeHumanSession
import email.testinbox.architecture.fixtures.RogueSessionFixture
import org.junit.jupiter.api.Test

class HumanSessionRuleTest {
    @Test
    fun `session writes are owned by the explicit lifecycle use cases`() {
        assertWriters(ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox"))
    }

    @Test
    fun `a concrete adapter issuer outside the use case fails the guard`() {
        val violation = ClassFileImporter().importClasses(RogueSessionFixture::class.java)
        val failure = runCatching { assertWriters(violation) }.exceptionOrNull()
        check(failure is IllegalStateException && RogueSessionFixture::class.java.name in failure.message.orEmpty())
    }

    private fun assertWriters(classes: JavaClasses) {
        val permitted =
            mapOf(
                "insert" to setOf(IssueHumanSession::class.java.name),
                "makeRoom" to setOf(IssueHumanSession::class.java.name),
                "touch" to setOf(AuthenticateHumanSession::class.java.name),
                "deleteAll" to setOf(RevokeAccountSessions::class.java.name),
                "delete" to
                    setOf(
                        IssueHumanSession::class.java.name,
                        AuthenticateHumanSession::class.java.name,
                        RevokeHumanSession::class.java.name,
                    ),
            )
        permitted.forEach { (method, allowed) ->
            val writers =
                classes
                    .flatMap { it.methods + it.constructors }
                    .filter { member ->
                        member.callsFromSelf.any {
                            it.targetOwner.isAssignableTo(HumanSessions::class.java) &&
                                it.name.substringBefore('-') == method
                        }
                    }.map { it.owner.name }
                    .toSet()
            check(writers == allowed) { "$method session writers $writers differ from $allowed" }
        }
    }
}
