package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.AccountRequestBudgets
import email.testinbox.application.usecase.ChargeAccountRequest
import email.testinbox.architecture.fixtures.RogueAccountBudgetFixture
import org.junit.jupiter.api.Test

class AccountBudgetWriterRuleTest {
    @Test fun `only the authenticated bounded application action charges account metadata`() {
        verify(ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox"))
    }

    @Test fun `a concrete adapter bypass cannot escape the account budget writer rule`() {
        val failure = runCatching { verify(ClassFileImporter().importClasses(RogueAccountBudgetFixture::class.java)) }.exceptionOrNull()
        check(failure is IllegalStateException && RogueAccountBudgetFixture::class.java.name in failure.message.orEmpty())
    }

    private fun verify(classes: JavaClasses) {
        val writers =
            classes
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any {
                        it.targetOwner.isAssignableTo(AccountRequestBudgets::class.java) &&
                            it.name.substringBefore('-') == "charge"
                    }
                }.map { it.owner.name }
                .toSet()
        check(
            writers == setOf(ChargeAccountRequest::class.java.name),
        ) { "account budget writers $writers differ from the authenticated action" }
    }
}
