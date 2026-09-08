# TI-004 — Inbound provider decision brief

**Status of this document:** decision input. **ADR-004 remains `Proposed`** and is
not marked Accepted here — §15 supplies replacement text for the owner to adopt.

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
