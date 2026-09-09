# TI-004 — Inbound provider decision brief

**Status of this document:** decision input — **now decided.** The owner issued an
authoritative decision on 2026-09-08; it is recorded verbatim in scope at the top
of §17 and it selects the recommendation below. **ADR-004 is still `Proposed` in
this repository** and Ops is not the party that changes it — per owner decision
item 13, the application team updates ADR-004 to `Accepted`. §15 supplies text;
the owner's decision supersedes it where they differ.

**Scope:** the production inbound-mail provider choice, and the internet-ingress
hardening that follows from it. No provider is implemented. No AWS resource is
created. No production deployment is performed.

Prepared by Ops against `develop@f300fc2`, the live staging environment, and the
real estate (Contabo US, Contabo EU, OVH).

---

## Errata — corrections after the application-side review

The application team analysed the same question independently
(`docs/adr/0004-inbound-provider-application-analysis.md`). They reached the same
recommendation for partly different reasons and found three errors in this
document. Two are material. They are corrected in place below; they are also
listed here so a reader who has the earlier revision knows what moved.

| # | What this brief said | Correction | Where |
|---|---|---|---|
| 1 | "`providerMessageId` is what stops a 4xx retry becoming a duplicate", and a message with no `Message-ID` is an unsolved dedup gap | **Wrong, and the gap does not exist.** `SmtpGateway` passes `providerMessageId = null` unconditionally; the uniqueness index is partial on `WHERE provider_message_id IS NOT NULL`; ADR-019 already decided that a message retried after a `451` is the *first successful delivery*, so there is nothing to deduplicate. | §"Replay and dedup", §5 |
| 2 | "The edge stamping a `Message-ID` when absent is the correct answer" | **Withdrawn — do not implement.** It would key suppression on a sender-controlled header, hiding exactly the duplicate-send defects the product exists to expose, and letting a third party choose our dedup key. | §"Replay and dedup", §5, §13, §15 |
| 3 | Unknown-recipient discard (ADR-025) listed as an available ingestion control under **both** options | **Wrong for SES and it is the strongest argument this brief had.** SES receipt requires an S3 write action, so mail to non-existent addresses is stored under our account before TestInbox can decide anything. ADR-025's "never stored" is structurally unavailable under any accept-then-store provider, and its loss re-widens the VISION.md §2 compliance question the owner is being asked. | §2, §3, §13 |

Two further points from that review are accepted as corrections of emphasis
rather than of fact, and are reflected in the text: the `451`/sender-owned-retry
argument (§0, §6) is real but carries roughly half the weight this brief gave
it, since what actually changes owner is liveness and observability rather than
durability; and flip-condition 2 in §14 ("the edge would be unowned") is
arguably **already met** by §11 Q11 — see §14.

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

**Five properties follow, and they are the axis the two options differ on:**

1. **One SMTP `DATA` transaction is one inbound event**, committed atomically
   across all its recipients (ADR-026).
2. **TestInbox controls acceptance.** It can refuse a message it cannot durably
   store, and the *sending MTA* — not TestInbox — owns the retry.
3. **Raw MIME is what the sender sent** (ADR-005 stores it before parsing).
4. **The production adapter is the adapter CI rehearses.** `SmtpGateway` is
   exercised on every pipeline run — though note this is true of the *ingestion*
   hop only; a relay edge in front of it is unrehearsed unless deliberately
   brought into CI (§13).
5. **Mail to an inbox that does not exist is never stored** (ADR-025). This
   property was missing from the original list and it is the one that decides
   the comparison — it presupposes property 2, because you can only decline to
   store what you were the one to accept.

Postfix preserves all five. SES preserves (1) and (3) only partially, and
structurally cannot preserve (2), (4) or (5). Properties 2 and 4 are trades.
**Property 5 is not a trade** — ADR-025 is Accepted, and an option that cannot
satisfy it is asking for a superseding ADR and a re-opened owner decision, not
offering a different balance of costs. That distinction is what §12a and §13
turn on.

The reframing that follows from this: the real axis is not *Postfix vs SES* but
**does TestInbox control acceptance, or does a provider accept on its behalf?**
Every managed inbound option — SES, Mailgun Routes, Postmark inbound, Cloudflare
Email Routing — sits on the same side of that line, and several avoid the
cloud-estate cost this brief scores heavily against SES specifically. They lose
to the same ADR-025 argument, and this brief should have named and dismissed
them on that ground rather than leaving "managed" reading as "AWS".

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

**Corrected — see Errata 1 and 2.** An earlier revision of this brief treated
this as an open design gap. It is not one, and the fix proposed here was harmful.

**No provider message identity is required on the Postfix path.**
`providerMessageId` stays `null` for `local-smtp`-class providers, the
uniqueness index stays inert (it is partial, `WHERE provider_message_id IS NOT
NULL`), and no `Message-ID` is stamped, read or trusted. ADR-019 already settled
each case the edge can produce:

| Situation | Behaviour | ADR-019 |
|---|---|---|
| Ingestion `451`s | Transaction rolled back, nothing committed; Postfix retries; the retry **is** the first successful delivery | Context case 2 — no dedup needed |
| Two genuinely separate byte-identical sends | Two rows, second annotated `possibleDuplicateOfMessageId` | Decision 4 |
| Commit succeeded, `250` lost in transit | Postfix retries, a second row appears | Decision 3 — "presenting both rows is the faithful outcome" |

The relay hop widens the third window slightly (a WireGuard hop can drop a `250`
more readily than loopback can). It does not create a new class of duplicate,
and ADR-019 deliberately chose to surface that window rather than risk
suppressing a real duplicate-send.

**Do not stamp `Message-ID`, and do not use it as a dedup key at all.** It is
sender-controlled: a sender that supplies one chooses our suppression key, and
duplicate `Message-ID`s across genuinely separate sends are precisely the
system-under-test defect ADR-019 exists to expose. A content hash is likewise
forbidden as a suppression key (ADR-019), which is why it appears only as an
annotation.

If the lost-`250` window is ever judged unacceptable, the mechanism to reach for
is an **edge-assigned transport identity** — the queue token in the *topmost*
`Received:` header, accepted only when that header names the trusted edge —
because it is assigned by us rather than by the sender, and is transport
metadata rather than content. Recorded as a contingency; not proposed for
adoption.

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

Real and ongoing: queue-depth and disk alerting, rDNS validity, TLS certificate
for STARTTLS, and log shipping off-host.

**Correction to an earlier draft of this brief:** it asserted the estate has no
automated patching. It does — `unattended-upgrades` is installed and enabled on
both audited hosts (`Update-Package-Lists "1"`, `Unattended-Upgrade "1"`), so an
edge built to the same standard inherits automatic security updates rather than
needing a new process. That materially reduces the "unpatched internet-facing
daemon" risk this brief weighs in §14.

What remains genuinely new is not patching but *mail* operations: queue health,
rDNS, blocklist watch. Estimate a few hours to build and under an hour a month
to run.

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

- **Identity**: SES `messageId` is stable and purpose-built. Note what this is
  and is not: SES *needs* it because SNS/SQS is at-least-once and genuinely
  re-presents the same provider event (ADR-019 Context case 1, which names SES).
  Postfix needs nothing because it does not re-present events. This is a
  requirement SES has and meets, not an axis on which it is ahead.
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
   would need recorded-event fixtures which, with LocalStack for S3/SQS, would
   in fact cover most of the adapter's risk surface — the residue is SES's own
   *receipt* behaviour (injected headers, unusual local-parts, its size limit),
   which is real but smaller than "never meets its provider outside production"
   suggests.
4. **ADR-025 cannot hold.** This is the largest consequence and an earlier
   revision of this brief missed it. An SES receipt rule must terminate in an
   action, and the action that yields raw MIME is `S3Action`. So mail addressed
   to a *non-existent* inbox is written to a bucket in our account, retained
   under our control, before TestInbox is invoked at all. ADR-025 is Accepted and
   says such content is never stored; under SES the strongest reachable
   guarantee is *stored, then deleted*. That is not a tuning question — it
   requires a superseding ADR, and it re-widens the VISION.md §2 compliance
   decision, which was narrowed on precisely the never-stored property. The same
   applies to Mailgun Routes, Postmark inbound and Cloudflare Email Routing: the
   real axis is not Postfix-vs-SES but **whether TestInbox controls acceptance
   or a provider accepts on its behalf**, and every managed option sits on the
   far side of that line.

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
| Unknown-recipient content liability | **Postfix: —. SES: control unavailable** | **discard in-process, never store (ADR-025)** — reachable only when TestInbox controls acceptance | — | Postfix: never written. **SES: already written before we decide** |

Three placements are worth calling out because getting them wrong is subtle:

- **Anti-enumeration is an INGESTION property, not an edge one.** The edge must
  not perform recipient existence checks, because a `550` for unknown recipients
  is exactly the oracle ADR-025 removes. The edge may only reject on *syntax*.
  Name the footgun concretely: **`relay_recipient_maps` must be left unset.** It
  is the *normal* configuration for a Postfix relay host — its documented purpose
  is to reject unknown recipients at the edge instead of generating backscatter —
  and switching it on silently reinstates the enumeration oracle. It belongs in
  the edge configuration review as an explicit prohibition, not as an omission.
  The residual that cannot be removed this way is a **timing side-channel**:
  the `250` is uniform but the latency behind it is not. Weak over internet SMTP
  and not worth engineering against today; recorded as a known residual so it is
  not rediscovered as a finding.
- **Archive bombs are defended by not decompressing.** There is no size check
  that saves you if you expand archives; the control is the decision not to.
- **SSRF is defended by not fetching.** Mail contains attacker-chosen URLs; any
  server-side fetch (link preview, image proxy, unfurl) turns every inbound
  message into an SSRF primitive. This should be an explicit non-goal.
- **The last row is not symmetric between the options, and an earlier revision
  of this table wrongly showed it as if it were** (Errata 3). ADR-025's control
  is *discard before storage*, and it presupposes that TestInbox decides whether
  to accept. Under SES, receipt requires an S3 write action, so every message to
  every non-existent address is written to a bucket in our account before our
  adapter is invoked; the earliest we can act is *delete after storage*, which is
  a different guarantee. This is structural, not a configuration gap, and it
  applies to every accept-then-store provider, not only SES.

---

## 4. Delivery semantics comparison

| Property | Postfix edge | SES |
|---|---|---|
| One DATA = one event | **exact** — same adapter | preserved via `receipt.recipients` |
| Multi-recipient atomicity | **exact** | preserved |
| Uniform `250` for unknown recipients | **exact** — same code path | preserved (domain-level rule accepts all) |
| TestInbox controls acceptance | **yes** | **no** — SES accepts first |
| ADR-025 "never stored" for unknown recipients | **holds** | **cannot hold** — S3 write precedes us |
| `451` reaches the sending MTA | **yes** | **no** — sender already got `250` |
| Retry owner | sending MTA (standard, free) | our SQS consumer (ours to build) |
| Size limit authority | `testinbox.maxRawSizeBytes` | SES's limit (~40 MB) |
| Wildcard / generated recipients | native | native (domain rule) |
| Exact-address reservation (ADR-021) | unaffected | unaffected |

---

## 5. Dedup implications

**Corrected — see Errata 1 and 2.** This section previously called SES "cleanly
ahead" on identity and described a Postfix dedup gap that does not exist.

- **SES**: `messageId` is provider-generated, always present, stable, and
  **load-bearing on the happy path** — SNS/SQS is at-least-once, so the same
  provider event is genuinely re-presented. Dedup is not a nicety here; the
  adapter is incorrect without it.
- **Postfix**: `providerMessageId` is `null`, the partial uniqueness index never
  engages, and **no mechanism is required**. A post-`451` retry is the first
  successful delivery (ADR-019 Context case 2); a lost-`250` retry produces a
  second row that ADR-019 decided to surface rather than suppress.

So this is not an axis on which SES is ahead. It is an axis on which SES carries
a requirement Postfix does not have, and satisfies it. The only genuine cost on
the Postfix side is the slightly wider lost-`250` window described in §1, which
ADR-019 already accepts. **This is no longer a blocking condition on the
recommendation.**

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
| 11 | **Does the provider choice change the *scope* of item 9?** | Owner + legal | **Yes.** VISION.md §2 narrowed the third-party-mail question on ADR-025's never-stored guarantee; an accept-then-store provider re-widens it to include mail to inboxes that do not exist | The owner must be told which question they are answering **before** they answer item 9. Added after the application review (Errata 3) |
| 12 | Provider spam/virus verdicts as retained annotations | Owner | SES only | If stored, we are recording a third party's judgement about a customer's mail. Harmless, but a conscious inclusion rather than a side effect |
| 13 | `possibleDuplicateOfMessageId` links messages across senders within one inbox | Owner | Existing behaviour, both options | A content-derived relationship stored about third-party mail; belongs on the retention list rather than being discovered later |

**Technical vs. legal, stated plainly:** the architecture can minimise what is
retained, for how long, and where. It cannot decide whether operating a service
that receives strangers' email is acceptable to this business.

---

## 11. Ops answers

**Corrected framing.** An earlier draft wrote this section as questions *for*
Ops. Ops is the author of this brief, so they are answered here. Only the items
marked **OWNER** are genuinely outside Ops' authority; everything else was
determined against the live estate.

| # | Question | Answer |
|---|---|---|
| 1 | Dedicated SMTP-edge VPS available? | **No spare host exists.** The estate is Contabo US `217.216.48.97` (shared control plane: GitLab, registry, monitoring, Vault, Keycloak, nkama-prod, TestInbox staging), Contabo EU `62.169.27.217` (privileged DinD runner — excluded by the brief), OVH `149.202.83.201` (production). A fourth host must be purchased. **OWNER** — procurement |
| 2 | Inbound port 25 permitted? | **Outbound 25 verified open** from both Contabo US and OVH (a public MX returned a `220` banner). **Inbound is a separate provider policy and is not determinable for a host that does not exist yet.** Testing it on an existing host would mean opening `:25` on the control-plane or production machine, which is not a measurement worth that exposure. **Confirm with the provider at purchase, before committing.** |
| 3 | Reverse DNS control? | Current PTRs are provider defaults — `vmi2932906.contaboserver.net`, `ns3019390.ip-149-202-83.eu`. Both Contabo and OVH expose PTR editing in their control panels, so this is **Ops-controllable once the host exists**. |
| 4 | Are the IPs clean on blocklists? | **Both estate IPs are not listed** on `zen.spamhaus.org`, `bl.spamcop.net` or `b.barracudacentral.org`. *Method note:* an initial check via a public resolver returned `127.255.255.254`, which is Spamhaus's **query-refused** code, not a listing — public resolvers are blocked. Re-run against a non-public resolver. **The new edge IP must be re-checked at purchase**: a recycled IP can arrive pre-listed, and that is a reason to reject an allocation. |
| 5 | DDoS protection for `:25`? | **Cloudflare cannot help — it does not proxy SMTP.** OVH includes anti-DDoS on dedicated servers; Contabo provides basic volumetric filtering. Neither is application-layer SMTP protection, so **connection and rate limiting must live on the edge itself** (§3). |
| 6 | Patching cadence? | **Already automated.** `unattended-upgrades` installed and enabled on both audited hosts. An edge built to the same standard inherits it. **Separate finding, unrelated to TI-004:** Contabo US also has `Automatic-Reboot "true"` at `04:30` — see §11a. |
| 7 | Can edge logs ship to the existing Loki? | **Not today.** Loki has no published port, sits on `monitoring_monitoring` only, and has no Traefik route. Options: run promtail on the edge pushing **over the WireGuard tunnel** (preferred — no new public surface), or expose Loki behind Traefik with authentication (adds public surface). **Ops design; tunnel-first.** |
| 8 | WireGuard or mTLS, and who operates it? | **Ops decision: WireGuard, operated by Ops**, with mTLS as a hardening follow-up (§1). |
| 9 | OVH tunnel port and `DOCKER-USER`? | `DOCKER-USER` on OVH is **still empty** (re-verified). WireGuard is a host-level UDP listener, so `DOCKER-USER` does not apply to it — but the **ingestion port is Docker-published, so it does**. Concrete constraint: the tunnel must be arranged so ingestion is reachable from the peer **without a `0.0.0.0` publish**, and the `DOCKER-USER` gap must be closed on OVH first (already an open production blocker). |
| 10 | Spool/backup policy for the edge? | **Ops decision: not backed up.** The queue is transient by design and short-lived by configuration, consistent with the deliberate no-backup stance for message content (ADR-009/025). |
| 11 | Who is on call for a mail-flow outage? | **No on-call exists.** There is no rota and no paging; alerting is Grafana → Brevo SMTP → `team@` email only. Mail failures are silent to users, so this is a real gap for an internet-facing edge. **OWNER** — see §14 condition 2 |
| 12 | Does an AWS account exist? | **No.** No AWS credentials, no `~/.aws`, no `AWS_*` in `/opt/infinity/secrets`. SES would require creating an account, IAM, and billing ownership from scratch — so §14's strongest SES-flipping condition is **definitively not met today**. |

### 11a. Incidental finding — automatic reboot on the shared host

Not a TI-004 matter, raised because it was found while answering Q6.

`Automatic-Reboot "true"` at `04:30` is set on Contabo US, which runs GitLab, the
container registry, monitoring, **Vault**, Keycloak, nkama-prod and TestInbox
staging. On an unattended reboot:

- containers return via `restart: unless-stopped`;
- **Vault returns sealed** — it is unsealed by `deploy.sh` reconcile, not at boot;
- TestInbox's **migrator does not run**, which ADR-029 §4 explicitly anticipates
  and is exactly why the request-time schema refusal exists.

The applications are designed for it. The unsealed-Vault window is the part
worth an owner decision, and it is independent of this brief.

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

### 12a. What the matrix looks like once Errata 3 is included

The table above is left as originally scored, because rescoring one's own matrix
after seeing the answer is worth nothing. But the reading beneath it does not
survive the correction, and that should be said plainly.

"Fit with TestInbox architecture" (weight 15, 5 vs 2) was scored on the `451`,
parity and fidelity arguments — the three the application review found
overweighted — and did **not** include the ADR-025 finding, because this brief
did not have it. That finding is of a different kind from the rest of the table:
every other row is a trade, and this one is a statement that one option cannot
satisfy an Accepted ADR at all, so choosing it requires a superseding ADR and a
re-opened human decision.

Scored independently by the application team, with *preservation of Accepted-ADR
invariants* as its own criterion at weight 22, the result is **74.4 vs 61.8** —
a margin that survives moving any single weight by one class, and collapses only
if that criterion is deleted.

**I accept that reading over my own.** The decision is determined — not by the
criteria I chose, but by whether the owner is willing to supersede ADR-025 and
widen the VISION.md §2 question. My "effectively a tie" conclusion stands only
inside a criteria set that omits the strongest fact in the comparison.

---

## 13. Recommendation

**Recommend: self-hosted Postfix edge on a dedicated VPS — conditional**, with
SES as a pre-analysed fallback rather than a rejected option.

The reasoning is not the matrix. It is four things, **reordered after the
application review** — the argument that now carries the recommendation is the
one this brief originally did not have, and the one it originally led with has
been demoted:

1. **ADR-025 is unavailable under any accept-then-store provider, and losing it
   re-opens a decision VISION.md records as settled.** SES receipt must write to
   S3, so mail to non-existent inboxes is stored under our account before we are
   invoked. This is structural and permanent; it converts an architecture choice
   into a compliance-scope change the owner has not been asked about. **This is
   the decisive argument** (Errata 3, §2, §12a).
2. **Parity with the rehearsed path — conditional on rehearsing the edge.** With
   Postfix, production ingestion is the `SmtpGateway` CI exercises on every run;
   with SES it is an adapter that meets its provider only in production. But the
   *edge itself* is unrehearsed under this proposal, and the edge is where the
   new risk lives. Worth having; not worth claiming until the relay hop is in the
   ephemeral rehearsal, which is why it is now a precondition rather than a
   bonus.
3. **The intake path is the worst thing in the system to migrate.** ADR-003's
   port keeps the *code* portable either way; what is not portable is the MX.
   Postfix keeps that ours.
4. **The usual objection to self-hosted mail does not apply.** "Self-hosted mail
   is a reputation nightmare" is about **sending** — SPF/DKIM alignment,
   blocklists, warm-up. TestInbox is **inbound-only**. It publishes
   `v=spf1 -all` and a DMARC reject policy precisely *because* it never sends,
   which is a strong, cheap, static posture. What remains is rDNS and port-25
   reachability — real, but far smaller than the reputation argument implies.

**Demoted:** the `451`/sender-owned-retry argument, which this brief originally
led with. It is mechanically accurate — SES does return `250` before we see the
message — but it is worth roughly half the weight given here. SQS redelivery is
queue configuration rather than a retry contract we design and build, and what
genuinely changes owner is *liveness and observability* of that queue, not
durability. It remains a reason; it is no longer the reason.

**Also demoted:** raw-MIME fidelity. Byte-exactness is unavailable under every
relay, Postfix included; the useful test is whether an addition could be
mistaken for system-under-test behaviour, by which SES's injected headers are a
minor cost — and this brief's own withdrawn `Message-ID` proposal would have
been the disqualifying one.

**This recommendation is conditional and should not be actioned until:**

- §10 compliance checkpoint is **approved** — otherwise both options are NO-GO —
  and the owner is told, before answering, that choosing an accept-then-store
  provider *widens the question being asked*, because ADR-025's never-stored
  guarantee cannot hold under one (Errata 3);
- Ops answers §11 Q1–Q3 affirmatively (edge VPS, inbound 25, rDNS);
- an **owner is named for the edge** as an operational class — §11 Q11 records
  that no on-call exists today, which makes this the condition most likely to
  fire (see §14 condition 2);
- the **edge joins the ephemeral CI rehearsal** before it carries production
  mail. The "production path is the rehearsed path" argument is true of the
  ingestion hop and false of the relay hop, which is where the new risk sits;
  a Postfix container in `scripts/staging-rehearsal.sh` earns the claim.

~~the missing-`Message-ID` dedup design (§5) is resolved~~ — **withdrawn.** There
is no such gap (Errata 1).

---

## 14. What would change the recommendation

Any **one** of these should flip it to SES:

1. **No inbound port 25**, or no rDNS control, at any available provider. Fatal.
2. **No appetite for a fourth host.** If the edge would be unowned and unpatched,
   SES is safer than a neglected Internet-facing daemon — an unpatched edge is
   worse than any lock-in.

   **This brief established the fact and then failed to apply its own condition**
   (Errata, closing note). §11 Q11 records that **no on-call exists today**, and
   §11 Q13 records that the shared host reboots automatically at 04:30 leaving
   Vault sealed with nobody paged. On the evidence in this document, condition 2
   is arguably **already met**. It does not flip the recommendation, for one
   reason and one reason only: Errata 3 shows the alternative cannot preserve an
   Accepted ADR, so the flip is not available without a superseding ADR and an
   owner decision. What it does mean is that **"name an owner for the edge" is a
   hard precondition rather than a nice-to-have** — it is promoted into §13, and
   it is the condition most likely to fire.
3. **Legal requires a named processor** with contractual guarantees and audit
   posture for third-party content. AWS supplies that; a VPS does not.
4. **Volume grows past a single edge**, or abuse traffic becomes a sustained
   operational load.
5. **An AWS account already exists** with owned billing and IAM. **Checked: none
   exists** (§11 Q12), so this condition is not met today and SES currently
   carries the full cost of standing up a cloud estate from nothing.
   *Downgraded:* this brief called it "the single biggest scoring swing". It is
   not. It lowers SES's setup cost and does nothing about ADR-025, so on the
   corrected reading (§12a) it is not close to decisive. The application review
   declines to list it as a flip condition at all, and that is the better call.

7. **The owner decides to accept the wider compliance scope** — i.e. answers
   VISION.md §2 in a way that permits storing mail addressed to non-existent
   inboxes. This is the condition that actually matters, and it was missing from
   this list. It deletes the primary argument (Errata 3), and with that
   neutralised the matrix returns to something near the tie originally reported.

   Note this is a genuine *decision*, not a technicality: it is the owner's to
   make, and it must be put to them as "do you want to supersede ADR-025?", not
   discovered afterwards as a consequence of a provider choice.
6. **Availability becomes a hard requirement** (e.g. an SLA), where AWS's
   buffering beats a single spool and running two edges is not wanted.

And two that would flip it *back* to Postfix even if a managed provider were
chosen: **a requirement for byte-exact raw MIME**, which no relay can provide but
which SES degrades furthest; and **ADR-025 being reaffirmed rather than
superseded** — i.e. a decision that unknown-recipient mail must never be
persisted, which no accept-then-store provider can honour.

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
   approved — noting that an accept-then-store provider would widen that
   decision's scope, because ADR-025's never-stored guarantee is structurally
   unavailable under one;
2. Ops confirming inbound port 25, reverse DNS control and a dedicated edge host;
3. a named owner for the edge as an operational class;
4. the edge being exercised by the ephemeral CI rehearsal before it carries
   production mail.

No new dedup mechanism is required: ADR-019 already governs every duplicate case
the relay path can produce, `providerMessageId` remains `null` for this provider
class, and `Message-ID` is neither stamped nor used as a dedup key.

The edge holds no application state, no credentials, and no route to the
application, database or object-storage networks. Public SMTP is NOT colocated
with the production application stack.

AWS SES receiving is retained as a **pre-analysed fallback**, not a rejected
option. The TI-004 brief records the exact conditions that select it.

## Alternatives considered

- **AWS SES receiving.** Stronger buffering, no port-25 exposure, a provider
  message identity its own at-least-once delivery requires, and it scales
  without us. Rejected as the default primarily because **ADR-025 cannot hold
  under it**: SES receipt must terminate in an S3 write, so mail to inboxes that
  do not exist is stored under our account before TestInbox is invoked, and
  adopting it therefore requires superseding an Accepted ADR and widening the
  compliance decision this ADR is conditional on. Secondarily because it moves
  acceptance to AWS — so 451 no longer reaches the sender and queue liveness
  becomes ours to watch — it injects X-SES-* headers into the raw MIME the
  product exists to preserve, and it points the product's intake path at a
  provider the estate does not otherwise use.
- **Managed inbound routing generally** (Mailgun Routes, Postmark inbound,
  Cloudflare Email Routing). Cheaper to adopt than SES — webhook delivery, no
  IAM, no bucket policy, no second control plane — and they neutralise most of
  what is scored against SES. Rejected for the same reason as SES and only that
  reason: each accepts on our behalf and stores before we see the message, so
  ADR-025 falls to all of them.
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
| 10 | **Supersede ADR-025, or reaffirm it?** Choosing any accept-then-store provider (SES, Mailgun Routes, Postmark, Cloudflare Email Routing) requires superseding it, and *widens the scope of decision 1* to include mail addressed to inboxes that do not exist. The owner must be told this **before** answering decision 1, not after. | Owner + legal | Decisions 1 and 2 |
| 11 | Accept that provider spam/virus verdicts, if stored, are a third party's judgement about a customer's mail retained by us | Owner | SES option |

**Ops has answered none of these and should not.** §11 lists what Ops *can*
answer; everything above is an owner decision.

---

## What was NOT done

No provider implemented. No AWS resource created. No MX record created. No
production deployment. ADR-004 not marked Accepted. No legal advice given.

---

## 17. Owner decision, and the Ops work it authorises

**Decided 2026-09-08.** The owner reviewed both analyses and the reconciliation
and issued an authoritative 13-point decision. Summarised here so this document
is self-contained; the decision itself governs.

| # | Decision | Effect on this brief |
|---|---|---|
| 1 | Internet-originated mail **approved in principle**, within the existing product boundary (inbound-only; tenant-owned inboxes; ephemeral content; not a disposable-mail service; not a relay; unknown-recipient mail not retained). Public activation still gated on privacy policy, terms, abuse process, retention documentation and legal review | §10 checkpoint is **cleared for design and implementation**, not for public activation |
| 2 | **Keep ADR-025.** Do not supersede or weaken it | Errata 3 / §2 / §12a decide the provider question. No accept-then-store provider is admissible |
| 3 | **Self-hosted Postfix**, dedicated SMTP edge, first production inbound provider. Managed inbound documented as fallback, not permanently rejected; adopting one later needs a new owner decision and an ADR supersession | §13 recommendation adopted, with its fallback framing intact |
| 4 | Edge topology: Internet → dedicated EU SMTP edge VPS → Postfix → authenticated private relay → ingestion on OVH. Minimum state and privilege. **Not** Contabo US staging, **not** the Contabo EU privileged CI runner, **not** the shared OVH application host | §1 architecture adopted, with the host exclusions made explicit |
| 5 | New small EU VPS **approved in principle**; Ops must confirm inbound TCP/25, stable public IPv4, PTR/rDNS control, firewall/network control and mail-compatible provider terms **before** provisioning. ~2 vCPU / 2–4 GB / 40–80 GB, capability over size | §11 Q1–Q3 are now an Ops action item. Answered in §18 — recommendation **a new Contabo EU VPS**, revised from an earlier Hetzner recommendation that rested on a factual error (§18.3) |
| 6 | The edge **must have a named human operational owner** before public MX. The Ops agent may implement and operate through automation but is not the accountable owner. Runbooks required | §13 precondition confirmed as a hard gate. **Still unfilled** |
| 7 | EU residency: edge in EU, data plane on OVH France. Production raw MIME must not traverse the US staging host | §11 Q7 constraint tightened |
| 8 | `testinbox.email MX 10 mx1.testinbox.email`, `mx1` A → edge IP. No MX during design/implementation. DNS-only, never Cloudflare-proxied | See §19 — this interacts with decision 9 and needs one more owner call before any record is created |
| 9 | `abuse@`, `security@`, `postmaster@testinbox.email` prepared and **monitored** before public ingress. Operational contacts, not tenant inboxes | See §19 |
| 10 | Raw MIME and attachments deliberately not backed up; configuration and operational state may be. No backup may silently retain content beyond the documented window | §10 item 10 answered |
| 11 | Public MX blocked until the Postfix relay hop is in the automated rehearsal, proving: active recipient accepted; nonexistent recipient body never persisted; multi-recipient behaviour; downstream 451/retry; size limits; connection/recipient abuse limits; end-to-end delivery | §13 precondition confirmed, and **strengthened** — the "nonexistent recipient never persisted" case makes ADR-025 an executable test rather than a claim |
| 12 | **Does not authorise production deployment.** TI-DEPLOY-001 blockers remain independent: OVH monitoring, backups, `DOCKER-USER`, storage isolation/quota, production secrets, deployment validation. No production MX, no public SMTP, no production traffic | Unchanged |
| 13 | Application team may move ADR-004 `Proposed` → `Accepted`, preserving the managed-provider analysis, the revisit conditions, and the original Proposed history | Not Ops's to action |

**What this authorises Ops to do now:** confirm an edge host provider (§18),
design the edge and its relay, build the rehearsal harness for decision 11, and
write the runbooks decision 6 requires.

**What it does not authorise:** purchasing or provisioning before §18's
confirmations are closed, creating any MX or `mx1` record, enabling public SMTP,
or any production deployment.

---

## 18. Decision 5 — edge host provider confirmation

### 18.1 The criterion that cannot be confirmed from documentation

Decision 5 asks Ops to confirm **inbound TCP/25** before provisioning. No
provider publishes an inbound-25 statement, because inbound 25 is the normal
case — what providers publish, uniformly, is **outbound** policy, since outbound
25 is the spam vector. Every "port 25 blocked" result for Hetzner, OVH and
Contabo refers to egress.

That has two consequences and they should be stated plainly rather than papered
over:

1. **Inbound 25 can only be confirmed empirically, on the real IP, after
   provisioning.** Any pre-purchase claim is inference. The honest way to satisfy
   decision 5 is therefore to confirm the four *documentable* criteria first,
   then provision into a **cancellation or short-billing window** and make
   inbound 25 the first test performed — before any DNS, any configuration, and
   before the host is treated as committed.
2. **Outbound 25 is not a requirement for this edge, and its absence is a
   safety property.** TestInbox never sends (`v=spf1 -all`, DMARC reject). The
   edge relays over a private authenticated channel, not over port 25. An edge
   that physically cannot open an outbound SMTP connection cannot be conscripted
   as a spam source if it is ever compromised — which is the single most likely
   bad outcome for an internet-facing MTA. **Recommend leaving outbound 25
   blocked and never requesting an unblock.** See §18.4 for the one thing that
   depends on it.

### 18.2 The four documentable criteria

| Criterion | Hetzner Cloud (Falkenstein/Nuremberg/Helsinki) | OVHcloud VPS (Gravelines/Strasbourg) | Contabo VPS (EU) |
|---|---|---|---|
| Stable public IPv4 | Yes — Primary IP is a separate resource that survives server rebuild | Yes — fixed for the VPS lifetime | Yes — fixed |
| PTR / rDNS control | Yes — Cloud Console → Networking, self-service | Yes — Manager, self-service | Yes — Customer Control Panel → Reverse DNS Management, self-service |
| Firewall / network control | Cloud Firewalls: stateful, enforced *outside* the VM, free, **inbound and outbound rules** | Partial — host-level only for VPS in practice; OVH's network firewall is oriented at dedicated/Additional IP | Contabo Firewall: network-level, in front of the server, free, deny-all inbound by default, port ranges and source CIDRs, one firewall attachable to many instances — **inbound only; outgoing traffic explicitly unrestricted** |
| Terms compatible with inbound mail | Yes — mail servers permitted; egress 25 gated behind an account-age + limit-request process | Yes — mail servers permitted; egress subject to an anti-spam system that can re-block a flagged IP, escalating to permanent | Yes — mail servers permitted; egress rate-limited (~25 msg/min reported) |
| EU region | Yes | Yes (and same country as the data plane) | Yes |

**Empirically established, not inferred:** outbound TCP/25 is **currently
unblocked** from the Contabo US host — verified by direct TCP connect to two
unrelated public MX hosts on 2026-09-08 (`142.250.31.27:25`,
`188.165.47.122:25`, both established; `1.1.1.1:25` timed out, as a negative
control). That establishes Contabo's account-level posture. It says nothing
about a *new* EU instance's inbound path, which is §18.1's point.

### 18.3 Recommendation

> **Revised.** An earlier revision of this section recommended Hetzner Cloud and
> made Contabo the runner-up, rejected on the grounds that it has no
> provider-level firewall. **That was factually wrong.** Contabo shipped a free
> network-level firewall on 2026-04-02 — in front of the server, deny-all inbound
> by default, port ranges and source CIDRs, attachable to multiple instances,
> covering new *and* existing VPS/VDS. It is the sole criterion the earlier
> recommendation rested on, so the recommendation is replaced rather than
> adjusted. What follows is the corrected version.

**A new Contabo EU VPS (Nuremberg), Cloud VPS class, ~2 vCPU / 4–8 GB / 50 GB.**
Reasons, in order:

1. **It does not add a fourth supplier to an estate that cannot staff its
   third.** Decision 6 requires a named human owner for the edge and there is not
   one yet; both analyses identified the unowned edge as *the condition most
   likely to actually fire*. A new provider means a new account, a new billing
   owner, new credentials, a new support relationship and a new status page —
   all of which land on the ownership gap that is already the largest open risk.
   Contabo is an existing relationship with a control panel this estate has
   already used, including rDNS self-service.
2. **The differentiator is gone.** Provider-enforced, network-level inbound
   filtering — deny-all by default, outside the VM, so it holds even with root on
   the host — is now available on both. For an edge whose entire public surface
   is one port, that closes the gap that the earlier recommendation turned on.
3. **The account's network posture is measured, not assumed.** Outbound TCP/25
   is verified open from our existing Contabo host (§18.2). A new Hetzner account
   would start with no history and a documented ~1-month probation on the mail
   ports. Neither fact decides anything on its own; the difference is that one is
   evidence and the other is a forecast.
4. **The resource target sits squarely in Contabo's range**, and cost is the
   least important criterion in this comparison — noted only so its irrelevance
   is on the record.

**What we give up, stated plainly, because it is real.** Contabo's firewall is
**inbound only** — outgoing traffic is explicitly unrestricted — whereas
Hetzner's supports egress rules *and* blocks outbound 25 by default on a new
account. §18.1 recommends the edge never be able to open an outbound SMTP
connection, precisely so that a compromised edge cannot be conscripted as a spam
source. On Contabo that control must be **host-resident** (an nftables egress
DROP on 25), which puts it inside the blast radius of the daemon it is
protecting: root on the edge can remove it.

That residual is accepted, on this reasoning: an attacker with root already has
outbound 443 — which we must allow for updates and monitoring — so egress
filtering does not prevent command-and-control or exfiltration either way. Its
unique value is narrow: it prevents one specific harm, spam emission, which is a
reputation and abuse-liability cost rather than a data one. Mitigations are a
host-level egress DROP on 25, a monitored abuse contact (§19), and
egress-connection alerting from the existing Prometheus path. **Recorded as an
accepted residual with a named mitigation, not as an absence.**

**Runner-up: Hetzner Cloud EU (Falkenstein or Helsinki).** Genuinely better on
egress control, and its hourly billing makes decision 5's "confirm before
committing" literal rather than approximate. It becomes the recommendation if
the owner weighs provider-enforced egress control above supplier consolidation,
or if §18.5 comes back negative.

**Not recommended: OVHcloud VPS.** It would satisfy EU residency in the same
country as the data plane and keep the supplier count unchanged, but it
concentrates the edge and the entire production data plane behind one provider,
and OVH's anti-spam system can re-block a flagged IP with escalation to
permanent — an availability dependency on an egress-reputation mechanism we do
not otherwise need, on the one host whose whole job is to be reachable.

### 18.5 One thing to confirm in the Contabo panel before provisioning

The firewall announcement says the feature covers "all new and existing VPS/VDS
instances", but this estate holds no Contabo API credential, so I have not
verified it against our own account. **Confirm in the Customer Panel under
Network Services → Firewall that the feature is present on this account** before
treating criterion 4 as met. If it is not — for example if it is gated to a
newer product line than our existing instances — the recommendation reverts to
Hetzner, because the sole reason Contabo displaced it would be false again.

### 18.6 Status of decision 5

**Not yet closed.** The four documentable criteria are confirmed for all three
candidates, subject to §18.5. The fifth — inbound 25 — is confirmable only after
provisioning. The sequence to close it:

1. Confirm the firewall feature on our Contabo account (§18.5).
2. Provision one Contabo EU instance and set its PTR.
3. **Test inbound 25 first**, before any DNS record, any Postfix configuration,
   and before the host is treated as committed.
4. If inbound 25 does not arrive, cancel and provision on Hetzner instead, where
   hourly billing makes the same test free.

The cost of being wrong at step 4 is one month of a small VPS. That is the real
reason the earlier revision's "hourly billing" argument was overweighted: it is
a genuine advantage measured in single-digit euros, and it was allowed to
outrank an argument about who will own the host.

### 18.4 A design point that decision 5 surfaces: the edge must never bounce

Postfix's default behaviour when a queued message exceeds `maximal_queue_lifetime`
is to return a non-delivery notification to the envelope sender. That is wrong
for this edge on three separate grounds, and it is worth fixing in the design
rather than discovering the first time ingestion is down for a long weekend:

- it **requires outbound 25**, which §18.1 recommends never enabling;
- it is **backscatter** to an address we have not verified, which is precisely
  the behaviour that gets a young MX blocklisted;
- it is a **weak information leak** against ADR-025 — an NDN tells the sender
  something about what happened downstream, where the uniform `250` was designed
  to tell them nothing.

The edge configuration must therefore suppress sender-directed NDNs and convert
queue expiry into an **operational alert** instead — a queue-age and queue-depth
signal into the existing Prometheus/Uptime Kuma path, firing well before the
lifetime expires. I have not fixed the exact Postfix parameters here because I
have not verified them against a running instance, and this document has already
been corrected once for asserting a mechanism that was not there; they belong in
the edge build with a test behind them. Recorded as a **required property of the
edge**, not a preference.

---

## 19. One conflict between decisions 8 and 9, needing an owner call before any DNS

Decision 8 puts `MX 10 mx1.testinbox.email` on the **apex**. Decision 9 requires
`abuse@`, `security@` and `postmaster@testinbox.email` to be monitored operational
addresses before public ingress. Those two cannot both hold as written, because
once the apex MX points at the edge, **every** address at `testinbox.email`
resolves to the edge — including the three operational ones — and the edge's
correct behaviour for a non-tenant address, under decisions 2 and 11, is to
accept with a uniform `250` and never persist it.

The result would be that `abuse@testinbox.email` silently discards. That is the
worst available outcome: RFC 2142 makes `postmaster@` mandatory, an unreachable
`abuse@` is how a new MX gets escalated to blocklisting rather than contacted,
and it fails decision 9's word "monitored" while appearing to satisfy it.

Two ways out. Both are cheap **now** and expensive after tenant addresses exist.

**Option A — tenant inboxes on a subdomain (recommended).**

```
inbox.testinbox.email   MX 10 mx1.testinbox.email   # tenant mail → edge
testinbox.email         MX …                        # apex → ordinary mailbox provider
mx1.testinbox.email     A  <edge-ip>                # DNS-only, never proxied
```

The edge is then authoritative only for tenant mail, so uniform-`250`-and-discard
is unambiguously correct and ADR-025 is enforced on exactly the traffic it was
written for. The apex keeps working through a conventional provider, so the three
operational addresses are real monitored mailboxes with no edge special-casing,
no aliases, and no outbound 25. It also means a future managed-provider decision
touches one subdomain rather than the company's whole mail identity.

Cost: tenant addresses become `something@inbox.testinbox.email`. That is a
**product** decision, not an Ops one — the address is customer-visible and
appears in every SDK example.

**Option B — keep the apex, special-case the three addresses at the edge.**
Tenant addresses stay `something@testinbox.email`. The edge carries a small,
explicitly-audited alias map for exactly `abuse`, `security` and `postmaster`,
diverting them before the ingestion relay. Costs: those three local-parts must be
permanently reserved against ADR-021 exact-address reservation, so the
application must know about them too; the divert path needs somewhere to go that
does not require outbound 25; and the edge grows a recipient-conditional branch,
which is the exact class of configuration §3 warns about — get it wrong in the
other direction and it becomes an enumeration oracle.

**Ops recommends Option A** and will not create any DNS record until the owner
chooses. Neither analysis covered this, and it is the one item in the decision
that gets materially harder to change later.

---

## 20. Owner finalization, 2026-09-09

A second owner decision closed the questions §18 and §19 left open. It governs;
where it differs from anything above, it wins.

| Clarification | Effect |
|---|---|
| **Hetzner Cloud EU is the preferred first edge provider** | §18.3's revision is overruled on the conclusion, and the runner-up becomes the choice. The correction in §18.3 stands as a record — the Contabo firewall fact was wrong when first written and the retracted arguments were genuinely weak — but the owner weighs **provider-enforced egress control** above supplier consolidation, which §18.3 itself named as the condition under which Hetzner is right |
| **A disposable VM may be provisioned solely to probe inbound TCP/25 and the network prerequisites; destroy immediately on failure; no DNS/MX during the probe** | Closes §18.6's sequencing question. The probe is specified in §21 |
| **Outbound TCP/25 stays blocked and must never be requested or unblocked** | §18.1 point 2 is now a standing rule rather than a recommendation, and §18.4's "the edge must never bounce" stops being a design preference and becomes a hard constraint — with egress 25 permanently unavailable, a DSN cannot be delivered even if Postfix tried |
| **Tenant mail domain is `inbox.testinbox.email`; apex stays on an ordinary operational mailbox provider** | §19 Option A adopted. The edge is authoritative only for tenant mail, so uniform-`250`-and-discard is unambiguously correct and ADR-025 is enforced on exactly the traffic it was written for |
| **The edge must never emit DSNs or bounces** | Confirms §18.4 |
| **Queue expiry is an operational alert plus discard** | Confirms §18.4's mechanism and settles what happens to the message: it is dropped, not returned. The alert must therefore fire far enough ahead of expiry that expiry is a bug rather than a routine event |
| **`postmaster@inbox.testinbox.email` must eventually be monitored, without introducing a recipient-enumeration response difference** | New constraint. Design consequence in §20.1 |

### 20.1 `postmaster@` on the tenant domain, without an enumeration oracle

The constraint is exactly right and it is the subtle part of decision 9. RFC 2142
makes `postmaster@` mandatory at any domain that receives mail, and
`inbox.testinbox.email` receives mail — so the subdomain split in §19 does not
remove the obligation, it moves it.

The trap is that the obvious implementation reintroduces the oracle ADR-025
exists to remove. If the edge learns to treat `postmaster@` specially *at SMTP
time* — a different code, a different timing, a `RCPT TO` that behaves unlike
every other recipient — then a prober can distinguish a handled address from an
unhandled one, and the uniform `250` stops being uniform.

**The property that makes this safe: the divert happens after acceptance, never
during it.** The edge's SMTP conversation is unchanged — every `RCPT TO` at
`inbox.testinbox.email` gets the same uniform `250` after DATA, whether it names
`postmaster`, a live tenant inbox, or an address that has never existed. Only
once the message is accepted and queued does the edge decide where it goes:

| Recipient | SMTP response | Post-acceptance disposition |
|---|---|---|
| `postmaster@inbox.testinbox.email` | uniform `250` | local Maildir on the edge |
| live tenant inbox | uniform `250` | relayed over the private channel to ingestion |
| unknown address | uniform `250` | discarded in ingestion, never persisted (ADR-025) |

Three properties follow, and all three should be asserted by the decision-11
rehearsal rather than assumed:

1. **No response difference.** The three rows are indistinguishable to the
   sender. This is testable directly — drive all three cases and assert the SMTP
   transcripts are byte-identical apart from the queue token.
2. **No timing difference that a remote attacker can use.** Local delivery and
   relay-to-ingestion are not equally fast. §3 already records the timing
   side-channel as a known residual over internet SMTP; adding one more
   disposition does not change its character, but the rehearsal should record
   the measured spread so it is a known quantity rather than a discovered one.
3. **No outbound 25 required.** `postmaster@` is delivered *locally* on the
   edge, not forwarded to an external mailbox — forwarding would need egress
   SMTP, which is now permanently unavailable. "Monitored" is then satisfied by
   shipping a notification over the existing HTTPS alerting path when the local
   mailbox is written to, not by relaying the mail itself. The mail stays on the
   edge; the alert travels.

`postmaster` must also be **permanently reserved** against ADR-021's exact-address
reservation on the `inbox.` domain, so no tenant can ever claim it. That is an
application change and belongs to the app team, not to the edge configuration —
if the edge alone knows the address is special, a tenant can reserve it and the
two views disagree.

---

## 21. The inbound-25 probe (owner-authorised, 2026-09-09)

Authorised scope: provision a minimum EU instance, prove inbound TCP/25 reaches
it from a genuinely external network, prove the provider firewall can block and
restore that path, confirm stable IPv4 and PTR control, confirm outbound 25 is
blocked, record evidence, and **stop**. No Postfix, no MX, no DNS, no production.

**Blocked on a prerequisite the owner must supply.** This estate holds no Hetzner
account: no `hcloud` CLI, no Terraform or OpenTofu state, and no `HCLOUD_*`
credential in `~/.config/infinity`, `/opt/infinity/secrets` or the stack tree.
Provisioning needs an account with a payment method and identity verification,
which Ops cannot create. The probe is written and ready to run unattended the
moment a read-write Cloud API token exists — see
`/opt/infinity/workspace/ti004-edge-probe.sh`.

**External vantage point.** Step 4 requires a genuinely external network. The
Contabo US host qualifies — different provider, different autonomous system,
different continent — so the inbound test is a real internet path, not a
same-provider shortcut.

**PTR proof without touching our DNS.** A PTR record lives in Hetzner's reverse
zone for the address they allocated, not in `testinbox.email`. Proving PTR
control therefore creates no production DNS and does not breach "no DNS/MX during
the probe". The probe sets a deliberately non-production value, verifies it by
reverse lookup, and clears it before teardown.

**Retention.** The probe VM must not become the production edge — it will have
had an unauthenticated listener bound to :25 and a throwaway key, with no
configuration management. The recommendation is to destroy it. There is one
question worth the owner's attention: Hetzner's Primary IP is a resource separate
from the server and can be kept after the server is deleted, for roughly €0.60 a
month. Keeping it would let TI-006 start on an address whose inbound 25, PTR
control and reputation are already verified, instead of re-probing a fresh one.
Ops defaults to destroying everything, per "disposable", and will retain the IP
only if asked.
