package email.testinbox.architecture.fixtures

import email.testinbox.domain.identity.HumanSession
import email.testinbox.persistence.JdbcHumanSessions

class RogueSessionFixture {
    fun bypass(
        store: JdbcHumanSessions,
        session: HumanSession,
    ) = store.insert(session)
}
