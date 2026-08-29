package email.testinbox.persistence

import email.testinbox.application.port.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(transactionManager: PlatformTransactionManager) : TransactionRunner {
    private val template = TransactionTemplate(transactionManager)

    override fun <T> required(block: () -> T): T = template.execute { block() }!!
}
