# TI-004 — Inbound provider decision brief

**Status of this document:** decision input. **ADR-004 remains `Proposed`** and is
not marked Accepted here — §15 supplies replacement text for the owner to adopt.

**Scope:** the production inbound-mail provider choice, and the internet-ingress
hardening that follows from it. No provider is implemented. No AWS resource is
created. No production deployment is performed.

Prepared by Ops against `develop@f300fc2`, the live staging environment, and the
real estate (Contabo US, Contabo EU, OVH).

---

## 0. The constraint that decides most of this

The comparison is usually run as "self-hosted mail is painful vs. SES is a
lock-in". For TestInbox that framing is wrong, because the application already
has a **specific, SMTP-shaped inbound contract** and it is expressed in code, not
prose. From `ReceiveInboundDelivery`:

```kotlin
data class Command(
    val envelopeFrom: String?,
    val recipients: List<String>,   // ONE event may fan out to several
    val raw: ByteArray,             // full raw MIME
    val provider: String,
    val providerMessageId: String? = null,
)
```

and from its contract comment:

> Infrastructure failures propagate as exceptions so the SMTP adapter can
> soft-fail (4xx) for the whole transaction and the sender retries — never a
> silent drop, never a partial commit.

`SmtpGateway` implements exactly that: uniform `250` after DATA whether or not
the recipient resolves (ADR-025), `451` when the schema is unavailable
(ADR-029 §4) or persistence fails, `552` when oversized, `553` only for
syntactic recipient rejection.

**Four properties follow, and they are the axis the two options differ on:**

1. **One SMTP `DATA` transaction is one inbound event**, committed atomically
   across all its recipients (ADR-026).
2. **TestInbox controls acceptance.** It can refuse a message it cannot durably
   store, and the *sending MTA* — not TestInbox — owns the retry.
3. **Raw MIME is what the sender sent** (ADR-005 stores it before parsing).
4. **The production adapter is the adapter CI rehearses.** `SmtpGateway` is
   exercised on every pipeline run.

Postfix preserves all four. SES preserves (1) and (3) only partially and
structurally cannot preserve (2) or (4). That is not a reason to reject SES — it
is the trade being made, and it should be made deliberately.

---

## 1. Architecture — self-hosted Postfix edge

**Not colocated with the OVH application stack**, per the increment brief and
per the blast-radius analysis already recorded for staging.

```
             Internet (MX)
                  │  :25
    ┌─────────────▼──────────────┐   dedicated VPS, no other role
    │  SMTP EDGE                 │   Postfix + rspamd/limits
    │  • RCPT syntax + domain    │   NO database, NO object storage
    │  • size / rate / conn caps │   NO application credentials
    │  • spool (transient only)  │
    └─────────────┬──────────────┘
                  │  WireGuard, edge → app only
                  │  SMTP over the tunnel to ingestion:2525
    ┌─────────────▼──────────────┐
    │  OVH — testinbox_internal  │  ingestion → Postgres, MinIO
    └────────────────────────────┘
```

### What state lives on the edge

**Only a transient Postfix queue, and deliberately nothing else.** No database,
no object storage, no application credentials, no MinIO keys. The edge is a
relay: it accepts, it forwards, it forgets.

The queue is the one piece of state, and it is *not* a durability tier we
design around — it is the buffer that lets the edge absorb a short ingestion
outage. Concretely: `maximal_queue_lifetime` short (hours, not the 5-day
default), because TestInbox inboxes have TTLs measured in minutes (ADR-009) and
a message delivered three days late is worthless to a test. A short lifetime
also bounds spool-exhaustion risk.

### How raw MIME reaches TestInbox

**Postfix relays the message over SMTP to the existing ingestion listener.** This
is the important design choice: the edge speaks the same protocol the
application already implements, so `SmtpGateway` is unchanged and the production
path is the CI-rehearsed path. No new adapter, no new event format, no new
serialisation of raw bytes.

Byte fidelity: Postfix relaying adds a `Received:` header (as every hop does)
and nothing else. The body and all original headers pass through unmodified.

### Authentication between edge and ingestion

The ingestion listener has no authentication — it is bound to loopback today
precisely because it trusts its network position. Three options, in preference
order:

1. **WireGuard point-to-point, edge → OVH only.** Ingestion binds the tunnel
   address; the host firewall permits `:2525` from the tunnel peer only. The
   trust boundary is the network, which is what the listener already assumes,
   and the tunnel is a single well-understood component.
2. **mTLS on the relay hop.** Postfix `smtp_tls_security_level=verify` with a
   client certificate; ingestion terminates TLS and requires a client cert.
   Stronger identity, but adds certificate lifecycle to the mail path.
3. SMTP AUTH over TLS. Weakest — a shared secret in the edge's config, and it
   authenticates a *session*, not a host.

**Recommend (1), with (2) as a hardening follow-up.** Not because mTLS is worse,
but because a WireGuard peer list is easier to reason about and audit than a
certificate chain embedded in Postfix configuration, and the failure mode is
loud rather than subtle.

### Replay and dedup

Postfix retries on 4xx. A retry replays the same message, and `providerMessageId`
is what stops that becoming a duplicate. **Postfix's queue ID is not stable
enough to use directly** — it can change across a queue-file rewrite. The
durable identity is the original `Message-ID:` header, which the edge must not
rewrite, plus the envelope recipient. ADR-026 already scopes dedup to
`(provider, providerMessageId, recipient)`, which fits.

Gap to close in implementation: a message with **no** `Message-ID` (legal, and
common from crude senders) has no stable identity. Options are to have the edge
stamp one before relaying, or to fall back to a content hash — note that a
content hash would be *content-based* suppression, which ADR-019 forbids as a
dedup mechanism. **The edge stamping a `Message-ID` when absent is the correct
answer**, and it is a real design task, not a detail.

### Backpressure and failure

- Ingestion returns `451` → Postfix queues and retries with backoff. The sender
  sees nothing; the message is not lost.
- Ingestion down entirely → same path, bounded by `maximal_queue_lifetime`.
- Edge disk fills → Postfix refuses new mail with `452`; senders retry. Requires
  a disk alert, and the queue lifetime above is the primary control.
- Edge down entirely → **secondary MX** absorbs it, or senders retry for hours
  to days on their own. A single edge is not a message-loss risk in the way a
  single web server is an availability risk; SMTP is designed for this.

### Firewall / network

- Inbound `:25` from anywhere (unavoidable — that is what an MX is).
- Outbound `:25` **not required** — the edge does not send. Blocking it removes
  the edge's usefulness as a spam relay if compromised.
- WireGuard `:51820/udp` to the OVH peer only.
- Egress otherwise limited to OS updates.
- The edge must **not** reach `testinbox_internal`, Postgres, MinIO, or any
  other application network. The tunnel terminates at the ingestion port.

### Patching and monitoring burden

Real and ongoing: Postfix + OS security updates on an Internet-facing daemon,
queue-depth and disk alerting, rDNS validity, TLS certificate for STARTTLS, and
log shipping off-host. Estimate a few hours to build and under an hour a month
to run — but it is a *new class* of thing to run, not more of an existing one.

---

## 2. Architecture — AWS SES inbound

**"SES sends a webhook" is not a thing.** SES receiving has a specific shape and
it materially affects the design.

```
        Internet (MX → inbound-smtp.<region>.amazonaws.com)
                  │
        ┌─────────▼─────────┐
        │  SES receipt rule │  domain verified; recipient conditions
        └─────────┬─────────┘
                  │  actions, in order
        ┌─────────▼─────────┐
        │ S3 action         │  raw MIME → bucket (SSE-KMS)
        │ SNS action        │  notification only (256 KB cap)
        └─────────┬─────────┘
                  │
        ┌─────────▼─────────┐
        │ consumer          │  Lambda→HTTPS, or TestInbox polls SQS
        └─────────┬─────────┘
                  │  cross-cloud, authenticated
        ┌─────────▼─────────┐
        │ OVH — ingestion   │  new SES adapter, S3 GetObject for raw
        └───────────────────┘
```

### What AWS actually requires

- **Domain verification** plus an **MX record** pointing at
  `inbound-smtp.<region>.amazonaws.com`. The MX is AWS's, not ours.
- **A receipt rule set** with recipient conditions. For TestInbox's
  wildcard/generated addresses, the rule matches the *domain*, which accepts all
  local-parts — this is what preserves ADR-025's uniform acceptance.
- **An S3 action.** Necessary, not optional: SNS message payloads cap at 256 KB,
  so SNS alone cannot carry raw MIME for anything with an attachment.
- **A notification path** — SNS → HTTPS endpoint, SNS → Lambda → our API, or
  SNS → SQS with TestInbox polling. **SQS polling is the right shape here**,
  because it is pull-based: it does not require OVH to expose an endpoint to
  AWS, and it degrades safely when OVH is down (messages wait in the queue).
- **IAM**: a role for SES to write the bucket; a principal for TestInbox to read
  objects and consume the queue. Long-lived access keys on OVH unless we federate
  — and there is no OIDC identity provider on OVH today, so realistically
  **static AWS credentials on the production host**.
- **Encryption**: SSE-S3 or SSE-KMS at rest; TLS in transit. KMS adds key policy
  management and a per-request cost.

### Raw MIME retrieval and fidelity

The S3 object is the message SES received — **plus headers SES adds**:
`X-SES-Receipt`, `X-SES-Spam-Verdict`, `X-SES-Virus-Verdict`,
`Authentication-Results` and similar. For a product whose value is *showing the
tester exactly what arrived*, this is a real fidelity loss, and it is not
removable — stripping them would be worse, since it would mean rewriting the
bytes ADR-005 exists to preserve verbatim.

### Message identity, recipients, retries, duplicates

- **Identity**: SES `messageId` is stable and purpose-built. Better than the
  Postfix case — no `Message-ID`-absent problem.
- **Recipients**: `receipt.recipients` carries the envelope recipients, so
  ADR-026's one-event-many-recipients property is preserved.
- **Retries**: SNS/SQS deliver **at least once**. Duplicates are expected, not
  exceptional, and `providerMessageId` handles them — this is the mechanism
  working as designed.
- **Failure when OVH is unavailable**: the message is durably in S3 and the
  notification waits in SQS. This is genuinely SES's strongest property: the
  buffer is AWS-grade and needs no operation from us.

### The structural cost

**SES accepts the message before TestInbox sees it.** By the time our adapter
runs, the sending MTA has been told `250` and is gone. Three consequences:

1. `451` on schema-unavailable (ADR-029 §4) **can no longer reach the sender.**
   The soft-fail becomes "leave it in SQS and retry", which works, but the
   retry contract moves from a battle-tested MTA to our own consumer loop.
2. `552` for oversized moves to SES's own limit (~40 MB; confirm against current
   AWS documentation) rather than `testinbox.maxRawSizeBytes`.
3. The production ingestion path is a **different adapter from the one CI
   rehearses**. The ephemeral rehearsal exercises `SmtpGateway`; an SES adapter
   would need recorded-event fixtures, which test the parser but not the
   provider's real behaviour.

### Regional constraint

SES inbound is available in a **subset** of regions — historically `us-east-1`,
`us-west-2`, `eu-west-1`, with others added over time. **Confirm against current
AWS documentation before relying on this.** For an EU-resident estate,
`eu-west-1` (Ireland) keeps processing in the EU, which matters for §10.

---

## 3. Threat model, and where each control belongs

Internet mail ingress is hostile by definition: anyone can send anything to a
published MX, unauthenticated. The controls below are placed at the layer that
can enforce them *cheapest and earliest*.

| Threat | EDGE / PROVIDER | INGESTION | APPLICATION | OBJECT STORAGE |
|---|---|---|---|---|
| SMTP connection flood | connection + rate caps per IP | — | — | — |
| Slow-loris SMTP | per-stage timeouts | socket timeouts | — | — |
| Oversized message | `message_size_limit` / SES cap | `552` at `maxRawSizeBytes` | — | quota |
| Recipient spraying | RCPT-per-session cap, tarpit | uniform `250` (ADR-025) | rate limits (ADR-027) | — |
| Tenant enumeration | — | **uniform `250`, no existence check** | no quota signal in SMTP (ADR-027 §1) | — |
| Malformed MIME / parser attack | — | parse in-process, `ParseFailed` is a normal outcome | — | raw kept regardless (ADR-005) |
| Header bomb | header count/size limits | parser bounds | — | — |
| Archive bomb | — | **no automatic decompression** | do not expand archives server-side | size accounting |
| Attachment payload / malware | provider AV verdict (SES only) | store, never execute | never render inline | isolated bucket |
| HTML content | — | — | **sandboxed `srcdoc` iframe + CSP (ADR-011)** | — |
| SSRF via parsed URLs | — | **never fetch URLs found in mail** | no link preview, no image proxy | — |
| Unicode / address normalisation | — | normalise once, compare normalised | reservation uniqueness (ADR-021) | — |
| Cross-tenant multi-recipient | — | one transaction, all-or-nothing (ADR-026) | workspace scoping | per-workspace prefix |
| Disk / spool exhaustion | short queue lifetime, disk alarm | — | — | — |
| Object-storage exhaustion | — | — | storage quota (ADR-027), TTL sweep (ADR-009) | **bucket quota + disk alarm** |
| Unknown-recipient content liability | — | **discard in-process, never store (ADR-025)** | — | never written |

Three placements are worth calling out because getting them wrong is subtle:

- **Anti-enumeration is an INGESTION property, not an edge one.** The edge must
  not perform recipient existence checks, because a `550` for unknown recipients
  is exactly the oracle ADR-025 removes. The edge may only reject on *syntax*.
- **Archive bombs are defended by not decompressing.** There is no size check
  that saves you if you expand archives; the control is the decision not to.
- **SSRF is defended by not fetching.** Mail contains attacker-chosen URLs; any
  server-side fetch (link preview, image proxy, unfurl) turns every inbound
  message into an SSRF primitive. This should be an explicit non-goal.

---

## 4. Delivery semantics comparison

| Property | Postfix edge | SES |
|---|---|---|
| One DATA = one event | **exact** — same adapter | preserved via `receipt.recipients` |
| Multi-recipient atomicity | **exact** | preserved |
| Uniform `250` for unknown recipients | **exact** — same code path | preserved (domain-level rule accepts all) |
| TestInbox controls acceptance | **yes** | **no** — SES accepts first |
| `451` reaches the sending MTA | **yes** | **no** — sender already got `250` |
| Retry owner | sending MTA (standard, free) | our SQS consumer (ours to build) |
| Size limit authority | `testinbox.maxRawSizeBytes` | SES's limit (~40 MB) |
| Wildcard / generated recipients | native | native (domain rule) |
| Exact-address reservation (ADR-021) | unaffected | unaffected |

---

## 5. Dedup implications

Both work with ADR-026's `(provider, providerMessageId, recipient)` scope, but
the identity source differs in quality:

- **SES**: `messageId` is provider-generated, always present, stable. Duplicates
  are *expected* (SNS/SQS at-least-once) and dedup is load-bearing on the happy
  path.
- **Postfix**: identity is the `Message-ID:` header. Duplicates are *rare* (only
  on 4xx retry). But **the header is optional**, and a message without one has no
  stable identity — an unsolved design point requiring the edge to stamp one
  before relaying. A content hash is **not** an acceptable fallback: ADR-019
  explicitly forbids content-based suppression.

SES is cleaner here. It is one of the few axes where it is clearly ahead.

---

## 6. Failure and retry behaviour

| Scenario | Postfix edge | SES |
|---|---|---|
| Ingestion returns 451 | edge queues, retries with backoff | consumer leaves message on queue |
| Ingestion down | edge spools to `maximal_queue_lifetime` | S3 + SQS hold indefinitely |
| Edge/consumer down | senders retry hours–days; secondary MX optional | AWS unaffected; backlog drains later |
| Postgres down | 451 → sender retries | consumer retries |
| Object storage down | 451 → sender retries | consumer retries |
| Message lost | requires spool loss **and** sender giving up | requires S3 loss |

Both are sound. SES's buffer is stronger and needs no operation. Postfix's is
adequate and standard — SMTP was designed around exactly this.

---

## 7. Raw MIME fidelity

- **Postfix**: byte-faithful apart from one added `Received:` header, which every
  hop adds and which is expected in any mail path.
- **SES**: adds `X-SES-*` and `Authentication-Results` headers to the stored
  object. Unavoidable and unremovable-without-rewriting.

For most products this is noise. For TestInbox — whose product promise is
showing a tester exactly what their system under test emitted — it is a small
but genuine degradation of the core artifact.

---

## 8. Cost and operations

**Cost is not a differentiator at expected volume and should not drive this.**

| | Postfix edge | SES |
|---|---|---|
| Fixed | ~€5–10/mo VPS | £0 |
| 10k msg/mo | included | ~$1 + S3 + SQS ≈ pennies |
| 100k msg/mo | included | ~$10 + storage + egress |
| Cross-cloud egress | none | S3 → OVH per message |
| Hidden | patching, monitoring, rDNS, TLS, spool | AWS account ownership, IAM, billing alarms, a second control plane |

The real cost difference is **operational shape**, not money. Postfix adds a host
to an estate that already runs hosts. SES adds a *cloud account* to an estate
that currently has none — with its own credentials, billing, monitoring and
failure modes, outside GitLab Ops.

---

## 9. Vendor lock-in

ADR-003's `InboundMailProvider` port means **the application code is not locked
in either way** — that is the abstraction doing its job.

The lock-in is operational, not architectural:

- **Postfix**: none. The edge is portable to any VPS with port 25.
- **SES**: the MX record points at AWS. Moving away means a DNS change plus a
  new edge, during which mail must not be lost. Recoverable, but it is the
  product's *intake path* — the single most disruptive thing to migrate.

Also: choosing SES answers a question `VISION.md` deliberately keeps separate.
ADR-030 already noted that picking AWS "would put a thumb on the scale" for
future infrastructure decisions. That is worth naming rather than absorbing.

---

## 10. Compliance decision checklist — **legal/business, not technical**

**This is not legal advice, and none of it is resolved by anything above.**
`VISION.md` lists receiving arbitrary third-party mail as a separate Human
Decision. Until it is approved, **public internet mail is NO-GO regardless of
which provider wins.**

What is already technically mitigated (and what remains open) is separated
deliberately:

| # | Decision required | Owner | Technical mitigation that exists | What remains a decision |
|---|---|---|---|---|
| 1 | Data retention for third-party content | Owner + legal | ADR-009 TTL, sweep, hard delete; ADR-025 never stores unknown-recipient mail | Is minutes-to-24h retention defensible for **non-customer** senders' content? |
| 2 | Privacy policy covering non-customers | Legal | — | A sender who never agreed to anything has their mail processed. Policy must say so |
| 3 | GDPR/CCPA controller/processor role | Legal | EU-resident processing achievable (OVH; SES `eu-west-1`) | Are we controller or processor for mail from a non-customer? |
| 4 | Abuse contact and process | Owner | — | `abuse@` mailbox, response SLA, who reads it |
| 5 | Unlawful content reporting obligations | Legal | ADR-025 minimises exposure for unattributed mail | Obligations for content attributed to a **customer's** inbox |
| 6 | Deletion guarantees | Owner + legal | TTL sweep deletes rows **and** blobs | Can we promise deletion to a third-party sender? On what timeline? |
| 7 | Geographic processing and storage | Owner | OVH Gravelines (FR); SES inbound region choice | Must all processing stay in the EU? Decides SES region — or excludes SES |
| 8 | Customer terms | Legal | — | Customers must warrant they only direct mail they are entitled to |
| 9 | Mail from non-customers | Legal | — | The core question. Everything else is downstream |
| 10 | Retention in backups | Owner | Postgres/MIME **deliberately not backed up** (ADR-009/025 reasoning) | Confirm that no-backup is acceptable, not an accident |

**Technical vs. legal, stated plainly:** the architecture can minimise what is
retained, for how long, and where. It cannot decide whether operating a service
that receives strangers' email is acceptable to this business.

---

## 11. Ops handoff — questions Ops must answer, not guess

These gate the Postfix option. **Do not assume any of them.**

| # | Question | Why it decides something |
|---|---|---|
| 1 | Is a dedicated SMTP-edge VPS available/approved (budget, provider, region)? | Postfix is NO-GO without it — colocating on OVH is refused in §1 |
| 2 | Does the provider permit **inbound** port 25, and is it blocked by default? | Many providers block **outbound** 25 by default and permit inbound; the two are separate policies and both must be confirmed |
| 3 | Can we set **reverse DNS (PTR)** for the edge IP? | Some senders reject or downgrade mail from hosts without matching rDNS |
| 4 | Is the IP clean on major blocklists, and is its history known? | A recycled IP can arrive pre-blocked |
| 5 | What DDoS protection exists at the provider for `:25`? | Cloudflare does not proxy SMTP; the edge is directly exposed |
| 6 | How is the edge patched, and on what cadence? | Internet-facing daemon; the estate has no automated patching today |
| 7 | Can edge logs ship off-host to the existing Loki? | A compromised edge must not own its own evidence |
| 8 | Is WireGuard (or mTLS) between edge and OVH acceptable, and who operates it? | The trust boundary of the whole design |
| 9 | Does OVH permit the inbound tunnel port, and does `DOCKER-USER` need a rule? | The known estate gap — the firewall must actually apply |
| 10 | Spool/backup policy for the edge — is a transient queue acceptable unbacked-up? | Consistent with the deliberate no-backup stance for message content |
| 11 | Who is on call for a mail-flow outage, and what is the expected response? | Mail failures are silent to users; nobody notices without monitoring |
| 12 | Is there an existing AWS account, or would SES require creating one? | If SES needs a new account, its operational cost is materially higher than §8 implies |

---

## 12. Weighted decision matrix

Weights set **before** scoring, and justified. Cost is weighted low because at
expected volume both options are negligible; architecture fit is weighted high
because this is the product's intake path.

| Criterion | Weight | Postfix | SES | Why |
|---|---|---|---|---|
| Security | 15 | 3 | 4 | SES removes our port-25 exposure; but adds an AWS surface and static creds on OVH |
| Operability | 15 | 3 | 3 | Genuinely comparable, different shapes: a host we know how to run vs. a cloud account nobody runs yet |
| Reliability | 15 | 3 | 5 | S3+SQS buffering is stronger than a spool and needs no operation |
| Fit with TestInbox architecture | 15 | 5 | 2 | Postfix preserves acceptance control, 451-to-sender, byte fidelity, and the rehearsed adapter |
| Mail-specific operational burden | 10 | 2 | 4 | rDNS, blocklists, spool, TLS, patching are all ours with Postfix |
| Implementation complexity | 10 | 3 | 3 | New VPS + relay + tunnel vs. new adapter + IAM + receipt rules + account |
| Provider dependence | 8 | 5 | 2 | SES owns the MX — the intake path is the worst thing to migrate |
| Fit with existing estate | 6 | 4 | 2 | Postfix reuses GitLab Ops CD, Loki, the VPS model; SES is a second control plane |
| Cost | 3 | 4 | 4 | Negligible either way at expected volume |
| Future scaling | 3 | 3 | 5 | SES scales without us |
| **Weighted total** | **100** | **69.0** | **67.0** | |

**The matrix is effectively a tie, and that is the honest result.** Two points
out of a hundred is inside the noise of my own scoring. Anyone who moves
"reliability" or "architecture fit" by one weight class flips it. It should
therefore **not** be read as "Postfix wins" — it should be read as *the decision
is not determined by the criteria; it is determined by the unknowns in §11 and
the checkpoint in §10.*

---

## 13. Recommendation

**Recommend: self-hosted Postfix edge on a dedicated VPS — conditional**, with
SES as a pre-analysed fallback rather than a rejected option.

The reasoning is not the matrix. It is three things the matrix compresses too
much:

1. **The application is built around controlling acceptance.** `451` reaching the
   sending MTA is how TestInbox guarantees "never a silent drop" without owning a
   retry loop. SES structurally removes that and replaces a battle-tested MTA
   retry with one we write and operate. That is a real transfer of risk to us,
   in exchange for a better buffer.
2. **Parity with the rehearsed path.** With Postfix, production ingestion is the
   `SmtpGateway` that CI exercises on every run. With SES it is an adapter that
   only ever meets its provider in production. Given how much of this project's
   quality comes from gates that actually fire, that is worth a lot.
3. **The usual objection to self-hosted mail does not apply.** "Self-hosted mail
   is a reputation nightmare" is about **sending** — SPF/DKIM alignment,
   blocklists, warm-up. TestInbox is **inbound-only**. It publishes
   `v=spf1 -all` and a DMARC reject policy precisely *because* it never sends,
   which is a strong, cheap, static posture. What remains is rDNS and port-25
   reachability — real, but far smaller than the reputation argument implies.

**This recommendation is conditional and should not be actioned until:**

- §10 compliance checkpoint is **approved** — otherwise both options are NO-GO;
- Ops answers §11 Q1–Q3 affirmatively (edge VPS, inbound 25, rDNS);
- the missing-`Message-ID` dedup design (§5) is resolved.

---

## 14. What would change the recommendation

Any **one** of these should flip it to SES:

1. **No inbound port 25**, or no rDNS control, at any available provider. Fatal.
2. **No appetite for a fourth host.** If the edge would be unowned and unpatched,
   SES is safer than a neglected Internet-facing daemon — an unpatched edge is
   worse than any lock-in.
3. **Legal requires a named processor** with contractual guarantees and audit
   posture for third-party content. AWS supplies that; a VPS does not.
4. **Volume grows past a single edge**, or abuse traffic becomes a sustained
   operational load.
5. **An AWS account already exists** with owned billing and IAM — this removes
   most of SES's operability cost and is the single biggest scoring swing.
6. **Availability becomes a hard requirement** (e.g. an SLA), where AWS's
   buffering beats a single spool and running two edges is not wanted.

And one that would flip it *back* to Postfix even if SES were chosen: **a
requirement for byte-exact raw MIME**, which SES cannot provide.

---

## 15. Proposed replacement text for ADR-004

> Supplied for the owner to adopt. **Not applied. ADR-004 remains `Proposed`.**

```markdown
# ADR-004: Inbound Provider Strategy

**Status:** Proposed — requires the human decision in VISION.md §7 and the
compliance checkpoint in the TI-004 decision brief.

## Context

MVP runs entirely locally via the SMTP adapter (unchanged). Production needs a
provider for internet-originated mail. TI-004 evaluated self-hosted Postfix
against AWS SES receiving against TestInbox's actual contract rather than
generically.

The application's inbound contract is SMTP-shaped: one DATA transaction is one
event committed atomically across recipients (ADR-026); TestInbox controls
acceptance and soft-fails 451 so the SENDING MTA owns the retry; raw MIME is
stored byte-faithfully before parsing (ADR-005); unknown recipients receive a
uniform 250 and their content is never stored (ADR-025).

## Decision (proposed)

Adopt a **self-hosted Postfix edge on a dedicated host**, relaying over a
private authenticated channel to the existing ingestion listener, conditional on:

1. the VISION.md compliance decision on receiving third-party mail being
   approved;
2. Ops confirming inbound port 25, reverse DNS control and a dedicated edge host;
3. a resolved dedup identity for messages lacking a Message-ID header.

The edge holds no application state, no credentials, and no route to the
application, database or object-storage networks. Public SMTP is NOT colocated
with the production application stack.

AWS SES receiving is retained as a **pre-analysed fallback**, not a rejected
option. The TI-004 brief records the exact conditions that select it.

## Alternatives considered

- **AWS SES receiving.** Stronger buffering, no port-25 exposure, better message
  identity, and it scales without us. Rejected as the default because it moves
  acceptance to AWS — so 451 can no longer reach the sender and the retry
  contract becomes ours to operate — it injects X-SES-* headers into the raw
  MIME the product exists to preserve, it makes the production adapter one CI
  cannot rehearse, and it points the product's intake path at a provider the
  estate does not otherwise use.
- **Public SMTP colocated on the production host.** Rejected: it places an
  unauthenticated, internet-facing, attacker-reachable parser on the same host
  as unrelated production applications, identity and secret services.

## Consequences

- A dedicated edge host must be provisioned, firewalled, patched and monitored.
  This is a new operational class for the estate.
- The production ingestion path is the SMTP adapter CI already rehearses.
- Raw MIME stays byte-faithful apart from one Received: header.
- Migration to SES later is an adapter plus a DNS change; ADR-003's port makes
  the application indifferent.
- ADR-004 cannot be Accepted until the conditions above are met.
```

---

## 16. Exact human decisions required

| # | Decision | Owner | Blocks |
|---|---|---|---|
| 1 | **Approve or refuse receiving arbitrary third-party mail** (§10) | Owner + legal | **Everything.** Both options are NO-GO without it |
| 2 | Provider: Postfix edge, or SES | Owner + architecture | ADR-004 Accepted |
| 3 | Fund and approve a dedicated edge VPS | Owner | Postfix option |
| 4 | Accept a new operational class (Internet-facing daemon) and name its owner | Owner + Ops | Postfix option |
| 5 | Create/confirm an AWS account and billing owner | Owner | SES option |
| 6 | Data-residency requirement (EU-only?) | Owner + legal | SES region, or excludes SES |
| 7 | Abuse contact address and response commitment | Owner | Public MX |
| 8 | Production hostname and MX naming | Owner + product | DNS |
| 9 | Confirm content is deliberately unbacked-up | Owner | Retention posture |

**Ops has answered none of these and should not.** §11 lists what Ops *can*
answer; everything above is an owner decision.

---

## What was NOT done

No provider implemented. No AWS resource created. No MX record created. No
production deployment. ADR-004 not marked Accepted. No legal advice given.
