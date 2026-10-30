package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.BetaWaitlist
import email.testinbox.application.usecase.ManageBetaWaitlist
import email.testinbox.architecture.fixtures.RogueWaitlistFixture
import org.junit.jupiter.api.Test

class BetaWaitlistWriterRuleTest {
    @Test fun `only the bounded application flow mutates waitlist state`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox")
        for (method in listOf("insert", "remove", "purge")) verify(classes, method)
    }

    @Test fun `a concrete adapter bypass cannot escape the single writer rule`() {
        val classes = ClassFileImporter().importClasses(RogueWaitlistFixture::class.java)
        val failure = runCatching { verify(classes, "insert") }.exceptionOrNull()
        check(failure is IllegalStateException && RogueWaitlistFixture::class.java.name in failure.message.orEmpty())
    }

    private fun verify(
        classes: JavaClasses,
        method: String,
    ) {
        val callers =
            classes
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.isAssignableTo(BetaWaitlist::class.java) && call.name.substringBefore('-') == method
                    }
                }.map { it.owner.name }
                .toSet()
        check(callers == setOf(ManageBetaWaitlist::class.java.name)) { "waitlist $method writers: $callers" }
    }
}
