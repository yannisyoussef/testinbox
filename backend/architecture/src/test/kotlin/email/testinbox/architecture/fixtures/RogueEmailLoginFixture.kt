package email.testinbox.architecture.fixtures

import email.testinbox.domain.identity.EmailLoginChallenge
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.VerifiedLoginIdentity
import email.testinbox.persistence.JdbcEmailLogins
import email.testinbox.persistence.JdbcHumanIdentities
import java.time.Instant

class RogueEmailLoginFixture {
    fun bypassIdentity(
        store: JdbcHumanIdentities,
        id: HumanAccountId,
        identity: VerifiedLoginIdentity,
    ) = store.create(id, identity, Instant.EPOCH)

    fun bypassChallenge(
        store: JdbcEmailLogins,
        challenge: EmailLoginChallenge,
    ) = store.insert(challenge)
}
