# Idempotent mutations — operating guide

Normative decisions live in
[ADR-033](../adr/0033-idempotent-mutations.md); the REST contract is
`backend/api/contract/openapi.yaml`. This page is the operational one.

## The problem it solves

A client cannot tell a lost response from a failed request. A `POST` that
commits and whose response disappears looks exactly like one that never
happened — and the safe-looking action, retrying, creates a second resource.

For an inbox that is a duplicate and a confused test. For an API key it is
worse: the credential is returned exactly once and cannot be recovered, so a
lost response leaves an **orphan credential** — one that exists, has real
authority, and that nobody holds and nobody knows to revoke.

## Using it

```bash
curl -X POST "$BASE/v1/inboxes" \
  -H "Authorization: Bearer $KEY" \
  -H "Idempotency-Key: ci-build-4711-create-inbox" \
  -H 'Content-Type: application/json' \
  -d '{"ttlSeconds":600}'
```

Both SDKs take it as an option and send it verbatim:

```ts
await client.createInbox({ ttlSeconds: 600, idempotencyKey });
```
```kotlin
client.createInboxBlocking(CreateInboxOptions(idempotencyKey = key))
```

## Choosing a key

- **16–255 printable ASCII, and no spaces** (`0x21`–`0x7e`). Opaque otherwise:
  the server infers nothing from it, so a UUID, a ULID or your own job id are
  equally valid. A test name works as a key only once it is slugified — `sends
  welcome email` is rejected, `sends-welcome-email` is not.
- **Derive it from something your own retry boundary can reproduce.** This is
  the part people get wrong. A key generated per *attempt* defeats the feature
  entirely, and a key generated inside a process that then dies protects
  nothing — the retry comes from somewhere that never saw it. A CI job id, a
  test name plus a run id (slugified), or a row in your own queue all work.
- **Unique per workspace, per operation.** Two CI systems in one workspace both
  using `build-${BUILD_NUMBER}` will collide with each other. That collision is
  an accident rather than an attack — anyone who can bind a key is already
  inside the tenancy boundary — but it is a confusing one, so prefix your keys.
- The SDKs deliberately do **not** generate one for you. A key you did not
  choose is a key you cannot reproduce. **Orphan-credential protection on
  `POST /v1/api-keys` is therefore opt-in**: a client that passes no key gets
  exactly the pre-TI-003 behaviour.

## What each answer means

| Response | Meaning | What to do |
|---|---|---|
| `201` + `Idempotency-Replayed: false` | It ran | Nothing |
| `201` + `Idempotency-Replayed: true` | Already done; this is that result | Nothing — you have the resource |
| `409 idempotency-key-reused` | Bound to a *different* request | **Never** retry with this key; use a new one |
| `409 idempotency-replay-unavailable` | Bound to a committed request this server cannot reproduce | **Never** retry with this key; use a new one |
| `409 idempotency-request-in-progress` | A concurrent identical request is running | Retry with the **same** key |
| `409 idempotency-secret-not-replayable` | Key creation only — already created | Revoke the named key, mint again with a fresh key |

`Idempotency-Replayed` is sent on `POST /v1/inboxes` only. `POST /v1/api-keys`
has no replayed `201` to mark: a duplicate there is always the
`409 idempotency-secret-not-replayable` above, because the secret cannot be
re-issued (ADR-033 §8).
| `400` | Malformed or repeated header, or an unsupported operation | Fix the request |

Discriminate on the problem `type`, never on the status code: the four
idempotency meanings above share it with `address-already-reserved` and
`quota-exceeded`, and their correct actions are mutually exclusive.

Both SDKs give the *actions* distinct exception types — in-progress (retry),
secret-not-replayable (revoke and re-mint) and terminal (use a new key) — so a
`catch` can branch without string matching. The two terminal types deliberately
share one exception, because they ask a caller to do the same thing; the
problem type is carried on it (`problemType` in Kotlin, `problem.type` in
TypeScript) for when you want to tell a key-scheme collision from a server that
could not reproduce its own result.

## Things worth knowing

**A refusal does not bind the key.** If your request is rejected — bad TTL,
quota exhausted, address taken — the key stays free. Fix the request and retry
with the same key. Recording those failures would freeze a transient problem
into the key for the whole retention window with no way to clear it.

**A replay is a record of what you created, not proof it still exists.** Replay
an inbox creation after the inbox has expired and you get the creation back;
fetching that inbox then returns `404`. The alternative is a retry that can no
longer learn what it created, which is worse.

**Records expire.** Six hours by default (`testinbox.idempotency.retention`).
After that the same key is a fresh request. Retention is deliberately not tied
to inbox TTL — they solve different problems.

**Key creation suppresses the duplicate; it does not replay the secret.** There
is no design that returns the lost credential while keeping the promise that
nothing retains it. What you get instead is certainty: exactly one credential
exists, you are told which, and you can revoke it. See ADR-033 §8 for the two
alternatives that were rejected and why.

## Operating

| Metric | Labels |
|---|---|
| `testinbox_idempotency_total` | `operation`, `outcome` — `EXECUTED`, `REPLAYED`, `CONFLICT`, `IN_PROGRESS`, `ROLLED_BACK` |

Worth watching:

- **`CONFLICT` climbing** means clients are reusing keys for different requests
  — usually a key scheme that is not unique enough.
- **`IN_PROGRESS` climbing** means duplicates are arriving while the first is
  still running. A little is normal under retry storms; a lot suggests the
  claim wait (`testinbox.idempotency.claim-wait`, two seconds by default) is
  short relative to how long the mutation takes.
- **`ROLLED_BACK` climbing** means clients are burning keys on requests that
  never commit — often a client that retries a validation error unchanged.

The key itself appears in **no** metric label, no audit field and no problem
body. It is customer-generated and may carry their identifiers.

### A hung node holds its key

The claim is released when its transaction ends, so a crashed node frees the
key immediately. A node that is *hung or partitioned* — rather than dead —
holds it until the database reaps the connection. No duplicate mutation is
possible either way, but the key is unavailable for that window.

Bound it on the database, not in the application:

```
idle_in_transaction_session_timeout = 30s
```

Set a few seconds above the longest legitimate mutation. Without it, the
default is effectively the TCP keepalive interval — hours.
