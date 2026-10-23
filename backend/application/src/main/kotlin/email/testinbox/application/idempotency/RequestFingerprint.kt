package email.testinbox.application.idempotency

import email.testinbox.application.Sha256
import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.CreateInbox
import email.testinbox.domain.idempotency.CanonicalRequest
import email.testinbox.domain.inbox.LocalPartPolicy

/**
 * The fingerprint that decides replay from conflict (ADR-033 §5).
 *
 * Derived from the use-case `Command`, not from the HTTP body: the body's JSON
 * whitespace, key order and header set are all irrelevant to what the request
 * *means*, and the command is already the canonical statement of that.
 *
 * `FingerprintCoverageTest` asserts by reflection that every property of every
 * supported command is either fingerprinted here or listed as deliberately
 * excluded. A hand-maintained field list rots silently, and the failure mode is
 * nasty: the next field added would be left out, and a retry that changed only
 * that field would replay the old result — the client would not get what it
 * asked for, with no error anywhere.
 */
object RequestFingerprint {
    /**
     * Properties intentionally outside the fingerprint, with the reason. Read
     * by the coverage test, so changing this is a deliberate, reviewable act.
     */
    val DELIBERATELY_EXCLUDED: Map<String, String> =
        mapOf(
            "CreateInbox.Command.workspaceId" to
                "the workspace IS the scope — records are already unique per workspace",
            "CreateApiKey.Command.actor" to
                "the actor's workspace is the scope and its project is fingerprinted; its identity " +
                "binds the record separately (ADR-033 §4a), and its scopes and expiry only ever " +
                "narrow the result, never widen it",
        )

    fun of(command: CreateInbox.Command): String =
        Sha256.hex(
            CanonicalRequest.encode(
                listOf(
                    "operation" to "create-inbox",
                    "projectId" to command.projectId.value.toString(),
                    "addressMode" to command.addressMode.name,
                    // The REQUESTED ttl, not the resolved one: resolution depends
                    // on deployment config, so fingerprinting the resolved value
                    // would make a config change spuriously conflict a
                    // perfectly legitimate retry.
                    "ttlSeconds" to command.ttlSeconds?.toString(),
                    "aliasHint" to command.aliasHint,
                    // The NORMALISED local part: `Foo` and `foo` produce the
                    // identical inbox, so fingerprinting the raw value would make
                    // a retry conflict with itself over letter case.
                    "localPart" to command.localPart?.let(::normalisedLocalPart),
                ),
            ),
        )

    fun of(command: CreateApiKey.Command): String =
        Sha256.hex(
            CanonicalRequest.encode(
                listOf(
                    "operation" to "create-api-key",
                    "projectId" to
                        command.actor.projectId.value
                            .toString(),
                    "name" to command.name?.takeIf { it.isNotBlank() },
                    // Sorted by the WIRE string, never the enum ordinal:
                    // reordering the enum would otherwise rewrite every
                    // historical fingerprint the moment a new artifact deploys.
                    "scopes" to
                        command.scopes
                            .map { it.wire }
                            .sorted()
                            .joinToString(","),
                    "expiresInSeconds" to command.expiresIn?.seconds?.toString(),
                ),
            ),
        )

    /**
     * Falls back to the raw value when the local part is invalid. The request is
     * about to be rejected anyway, and a rejection never binds a key
     * (ADR-033 §4), so an invalid request's fingerprint is never stored.
     */
    private fun normalisedLocalPart(raw: String): String =
        when (val validation = LocalPartPolicy.validate(raw)) {
            is LocalPartPolicy.Result.Valid -> validation.normalized
            else -> raw
        }
}
