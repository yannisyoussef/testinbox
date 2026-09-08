# API keys — operating guide

Normative decisions live in
[ADR-032](../adr/0032-api-key-credential-lifecycle.md) and
[ADR-010](../adr/0010-authentication-api-keys.md); the REST contract is
`backend/api/contract/openapi.yaml`. This page is the operational one: what to
run, what to expect, and what cannot be undone.

## The credential

```
ti_k1_<publicId>_<secret>_<check>
```

- **`ti_k1_`** — product marker and format version. Grep for it in a diff, a
  log, or a secret-scanning rule.
- **`publicId`** (16 chars) — the non-secret handle. Safe to display, log and
  quote in a ticket. It is what the database is indexed by and what appears in
  audit lines. It encodes nothing: not the workspace, not the project, not the
  scopes.
- **`secret`** (52 chars) — 260 bits of CSPRNG entropy. This is the part that
  grants access.
- **`check`** (4 chars) — a CRC-32 so a truncated or mistyped paste is rejected
  locally with a clear reason instead of becoming an unexplained `401` in
  someone's pipeline. It is not a security control.

## The secret is shown once and cannot be recovered

`POST /v1/api-keys` is the only response that has ever contained the
credential, and the server does not store it. There is no "show key", no
"resend", and no support path — the information required to answer does not
exist anywhere in the system.

**A lost key is replaced, not recovered:** mint a new one, deploy it, then
revoke the old one.

## Scopes

| Scope | Grants |
|---|---|
| `inboxes:write` | Create and delete inboxes |
| `messages:read` | Read messages, wait, download raw MIME and attachments |
| `api-keys:manage` | The whole key lifecycle |

Give a CI credential `inboxes:write` and `messages:read` and nothing else. A
key can never grant a scope its creator does not itself hold, so
`api-keys:manage` is not a superuser scope by accident — a key holding only it
cannot mint itself an `inboxes:write` key.

Scopes are immutable. Changing what a credential may do means minting a new
one, which keeps "what could this key do at the time?" answerable from the
audit log.

## Rotation

There is no `rotate` endpoint, deliberately: rotation's hard part is the
interval during which the client has not yet picked up the new credential, and
no server call can shorten it.

```
1. mint the replacement        POST   /v1/api-keys
2. deploy it to the client     (your CI secret store)
3. verify the client works     (watch it succeed)
4. revoke the original         DELETE /v1/api-keys/{id}
```

Between 1 and 4 both credentials are live, which is what makes the rotation
zero-downtime. Do not compress the sequence: revoking before the client has
picked up the replacement is the only way to create an outage here.

## Revocation

`DELETE /v1/api-keys/{id}` sets `revokedAt`. The row is **retained** — a
compromised credential's history is the evidence, and deleting it destroys
that at the moment it becomes interesting.

- It takes effect on the **next request, on every node**. No authorization
  state is cached anywhere, so there is nothing to invalidate and no staleness
  window to wait out.
- It is **idempotent**: revoking an already-revoked key returns `204`, so a
  client retrying after a network failure is never told its own successful call
  failed. `revokedAt` in the metadata says which case it was.
- Revoking the workspace's **last administrative key is permitted**. Refusing
  would protect against lockout at the cost of forbidding the one operation you
  most need when that key is the compromised one — see break-glass below.

## `lastUsedAt` is approximate

Refreshed at most once per credential per five minutes, and it may lag by that
much after a burst. It exists to answer "is anything still using this?" before
you revoke it. It is **not** an audit record and must never be used as an
authorization input; the audit log is on the `testinbox.audit` logger.

A key with `lastUsedAt` unset has not been used since the field existed —
which, for a key minted after TI-002, means it has never been used at all.

## Bootstrap and the first key

A fresh installation has no credential to authenticate `POST /v1/api-keys`
with, so `testinbox.bootstrap.api-key` exists. It is **conditional, not
permanent**:

> The bootstrap credential authenticates only while its workspace holds no
> usable managed key carrying `api-keys:manage`.

Initialising an environment therefore looks like this:

```bash
curl -sS -X POST "$BASE/v1/api-keys" \
  -H "Authorization: Bearer $TESTINBOX_BOOTSTRAP_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ops-admin","scopes":["api-keys:manage","inboxes:write","messages:read"]}'
```

The moment that returns, the bootstrap key stops authenticating — no restart,
no configuration change, and therefore nothing an operator can forget to do.
Store the returned credential immediately; it will not be shown again.

**Break-glass.** If every managed administrator is revoked or expires, the
bootstrap credential starts working again. That is the recovery path, and it is
why revoking a compromised last administrator is allowed. It follows that:

- Keep `testinbox.bootstrap.api-key` configured in any environment you would
  need to recover.
- **An environment with no bootstrap key configured has no break-glass.**
  Revoking its last administrative key locks the workspace out of key
  management permanently, and there is no server-side path back in.

### Retiring or rotating the bootstrap credential

A bootstrap credential authenticates only when the presented token matches the
value the process is **currently configured** with. Configuration is therefore
the retirement mechanism, and it is the whole mechanism:

- **Rotate:** set `testinbox.bootstrap.api-key` to a new value and restart. The
  previous credential stops authenticating on the next request. (Its row stays
  in the table — revoked keys always do — but it is inert, because it can never
  again match a configured value.)
- **Retire entirely:** unset the setting and restart. There is then no
  break-glass at all, which is exactly what the line above promises.

There is deliberately no API for this. A bootstrap credential is never returned
by `GET /v1/api-keys` and cannot be revoked through `DELETE` — the management
API describes managed credentials, and offering a control over one it does not
otherwise expose would be worse than the configuration path being the only one.

**Generate it randomly.** ADR-032 §2's argument for SHA-256 over a password
hash rests on the secret being high-entropy; managed keys get that by
construction, and the bootstrap credential gets it only from you. A deployed
node refuses to start with a bootstrap key shorter than 43 characters, one
using fewer than 16 distinct characters, one matching a known development
fixture, or one beginning `ti_` (which would route it to the managed-credential
path, where it could never authenticate). Use:

```bash
openssl rand -base64 32
```

The transition is audited: `event=bootstrap.superseded` is logged at WARN every
time the bootstrap credential is presented after a managed administrator
exists. A run of those after a cutover means something in the estate is still
shipping the old secret.

## Rate limits

Key administration is charged against its own `KEY_ADMIN` category, which is
deliberately tight (burst 20, ~1 per 5s sustained): a rotation is a handful of
calls. Configure it with `testinbox.limits.key-admin.*` if a legitimate
workflow needs more.

Per-key rate limiting is **not** implemented — budgets remain workspace-scoped,
so minting keys cannot increase a workspace's capacity. See
`docs/security/abuse-model.md` for the follow-up's shape.

## Audit trail

Credential lifecycle events go to the `testinbox.audit` logger, so a deployment
can route them with different retention and access control from application
logs without filtering on message text:

| Event | Level | When |
|---|---|---|
| `api_key.created` | INFO | A credential was minted |
| `api_key.revoked` | INFO | A revocation, with `alreadyRevoked` distinguishing a retry |
| `bootstrap.superseded` | WARN | The bootstrap credential was presented after its window closed |

Every line carries the workspace, the acting credential's id, the affected
key's id and the request's correlation id. None of them can carry the
credential, the `Authorization` header or the stored verifier — the audit event
type has no field to put one in, and `DependencyRuleTest` fails the build if
one is added.

## Metrics

On the private management port only (`/actuator/prometheus`, never routed by
the edge):

| Metric | Labels |
|---|---|
| `testinbox_api_key_auth_total` | `outcome` — `SUCCESS`, `BOOTSTRAP`, `BOOTSTRAP_SUPERSEDED`, `REVOKED`, `EXPIRED`, `BAD_SECRET`, `UNKNOWN_KEY`, `MALFORMED`, `CHECKSUM_MISMATCH`, `UNSUPPORTED_VERSION` |
| `testinbox_api_key_lifecycle_total` | `operation` — `CREATED`, `REVOKED`, `REVOKE_NOOP` |
| `testinbox_api_key_last_used_writes_total` | — |

Two are worth watching:

- **`outcome="REVOKED"` climbing** means something is still presenting a
  credential you retired — a pipeline that missed a rotation, or an attacker
  replaying a leaked key.
- **`api_key_last_used_writes_total` tracking request volume** means the
  coalescing has stopped working and the authentication path has silently
  acquired a per-request database write.

No label identifies a particular credential. The public id is safe to log but
would be an unbounded, caller-chosen metric label — an attacker presenting
fabricated credentials would be deciding how many series the metrics backend
allocates. Per-credential attribution is what the audit log is for.

## Verifying a deployed environment

```bash
cd deploy/synthetic
TESTINBOX_BASE_URL=https://staging.testinbox.email \
TESTINBOX_ADMIN_API_KEY=…  \
TESTINBOX_API_KEY=…        \
TESTINBOX_SMTP_HOST=127.0.0.1 TESTINBOX_HTTP_BASE_URL=http://staging.testinbox.email \
  npm run test:product
```

It mints two short-lived credentials, proves one authenticates, revokes it,
proves the other still works, and revokes everything it created. Each run
leaves two inert (revoked, expired) rows behind.

This is **separate** from the deployment gate (`npm test`) on purpose: the gate
runs with a least-privilege credential, because a key that can mint keys is a
much larger thing to leave in an automated runner.
