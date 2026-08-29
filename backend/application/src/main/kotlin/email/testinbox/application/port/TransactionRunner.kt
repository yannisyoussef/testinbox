package email.testinbox.application.port

/** Runs [block] inside a database transaction (REQUIRED propagation). */
interface TransactionRunner {
    fun <T> required(block: () -> T): T
}
