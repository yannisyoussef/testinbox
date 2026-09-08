# TI-004 — Inbound provider: application-side analysis

**Status of this document:** decision input. **Acted on — the owner decided on
2026-09-08 and [ADR-004](0004-initial-inbound-provider-strategy.md) is now
Accepted** (dedicated self-hosted Postfix relay edge; ADR-025 kept and not
weakened; managed providers retained as a documented fallback).

This document is retained unchanged below as the reasoning the decision was made
against, and because the conditions that would reopen the choice live here. It
is a record, not a live proposal: where it says "recommend" or "proposed", read
it as what was put to the owner, not as something still pending. §10's ADR text
was the input to the accepted ADR rather than its final wording — the ADR itself
is authoritative.

**Scope:** the production inbound-mail provider choice, judged against what the
application actually promises and actually does. No provider implemented, no
AWS resource created, no MX record created, no production deployment, no
ingestion or idempotency semantics changed.

**Relationship to the Ops brief:** written after reading
[PR #23](https://github.com/yannisyoussef/testinbox/pull/23)
(`docs/adr/0004-inbound-provider-decision-brief.md`), deliberately not derived
from it. Where I reached the same conclusion by different reasoning I say so,
because two analyses that agree for the same reason are one analysis counted
twice.

Prepared against `develop@f300fc2`. The estate facts in the TI-004 brief §1 are
taken as given and are not re-derived.

**Headline:** I reach the **same recommendation** as Ops — a self-hosted
Postfix edge, conditionally — and I get there **almost entirely differently**.
The argument Ops rests its case on is weaker than presented; the argument that
actually decides it is one the Ops brief does not make. One of its proposed
implementation mechanisms would violate an Accepted ADR and break the product's
core promise, and should not be adopted.

---

## 1. Where I agree

- **The comparison must be run against TestInbox's actual contract, not
  generically.** The brief's §0 framing is right and is the reason the document
  is useful at all.
- **Public SMTP must not be colocated** with the OVH production stack.
- **The edge holds no application state, no credentials and no route to the
  application networks.** Correct, and the WireGuard-first recommendation with
  mTLS as hardening is the right order for an estate with no PKI.
- **Cost is not a differentiator** and should not drive this.
- **The reputation objection to self-hosted mail is about *sending*.** TestInbox
  is inbound-only and publishes `v=spf1 -all`. This is the single most commonly
  misapplied argument in this space and the brief is right to defuse it.
- **Anti-enumeration belongs at ingestion, archive bombs are defended by not
  decompressing, SSRF by not fetching.** All three confirmed against the code
  (§6).
- **Compliance is a human decision and public mail is NO-GO until it is made**,
  whichever provider wins. Correct, and I have added to the checklist rather
  than re-litigated it (§6.4).
- **ADR-004 must not be marked Accepted** by either analysis.

---

## 2. Where I disagree

### 2.1 The claim the recommendation turns on is half-true, and the half that is false is the half being used

The brief's §13 reason 1, and its §0 framing, rest on:

> `ReceiveInboundDelivery` propagates infrastructure failures so `SmtpGateway`
> can `451` the DATA transaction and the sending MTA owns the retry. SES accepts
> before TestInbox sees it, so `451` can never reach the sender, and the retry
> contract becomes ours to build and operate.

**Verified against the implementation. The mechanism is real:**

- `ReceiveInboundDelivery.execute` does no exception handling of its own, so
  storage and database failures propagate
  (`backend/application/src/main/kotlin/.../usecase/ReceiveInboundDelivery.kt`).
- `SmtpGateway.data` catches `Exception` and throws `RejectException(451, …)`,
  and separately `451`s when `schema.status()` is incompatible — ADR-029 §4
  (`backend/ingestion/src/main/kotlin/.../smtp/SmtpGateway.kt:96-118`).
- The recipient rows of one event commit in one transaction, so a `451` leaves
  nothing partially committed.

So the description is accurate. **What is not accurate is its weight**, and the
brief overstates it in three ways.

**First — "the retry contract becomes ours to build" overstates what SES
requires of us.** Under SES the message is durably in S3 and the notification
waits in SQS. A consumer that fails to process simply does not delete the
message, and SQS redelivers after the visibility timeout. That is not a retry
loop we design; it is the default behaviour of the queue, and it is closer to
"configure a visibility timeout and a dead-letter queue" than to "build a retry
contract". The brief's own §6 table says as much ("consumer leaves message on
queue") and then §13 characterises the same fact as a significant transfer of
engineering risk. Both cannot be right.

**Second — "never a silent drop" survives the move, and the brief nearly says
so.** Under SES the message is in S3 before anything can go wrong on our side.
The failure mode is not a drop; it is a *backlog*, which is more visible than a
451 storm, not less. What genuinely changes owner is **liveness**: with Postfix
the sender gives up after its own retry horizon and the message is gone with a
bounce to a real human; with SES the message sits in SQS until we drain it or
its retention expires (14 days max) — silently, because SQS depth is a metric
nobody is currently watching, and §1 establishes there is no on-call.

That is a real cost of SES, but it is an **observability and on-call** cost, not
a durability one. Stated correctly it is smaller than the brief implies, and it
lands in the same place as the brief's own flip-condition 2.

**Third — and this is the substantive error — the brief misunderstands why its
own strongest argument is sound.** It presents `providerMessageId` as what
protects a 451 retry from becoming a duplicate. Nothing does, and nothing needs
to: after a 451 nothing was committed, so the retry is the first *successful*
delivery, not a second copy of a stored one (§2.2). The 451 path is safe — but
not for the reason given. That matters, because the misunderstanding is what
produced the `Message-ID`-stamping proposal, which would have broken the
product's core promise to fix a problem that does not exist.

**Judgement: the 451 argument is real but is not decisive.** It is worth roughly
half the weight the brief gives it. It should not be the argument the
recommendation turns on — and it need not be, because a stronger one exists
(§2.3).

### 2.2 The brief's dedup "gap" is not a gap — and its proposed fix would break the product

The brief §1/§5 says:

> Postfix retries on 4xx. A retry replays the same message, and
> `providerMessageId` is what stops that becoming a duplicate. […] a message with
> **no** `Message-ID` […] has no stable identity. […] **The edge stamping a
> `Message-ID` when absent is the correct answer**, and it is a real design task,
> not a detail.

Every load-bearing sentence of that is wrong, and the code and ADR-019 both say
so.

**`providerMessageId` is not what stops a 4xx retry duplicating — nothing is,
and nothing needs to be.** `SmtpGateway` passes `providerMessageId = null`
unconditionally (`SmtpGateway.kt:109`), and the dedup index is partial:

```sql
-- V2__recipient_scoped_provider_delivery.sql
ON message (provider, provider_message_id, envelope_to)
WHERE provider_message_id IS NOT NULL;
```

with the insert doing `ON CONFLICT … WHERE provider_message_id IS NOT NULL DO
NOTHING`. With a NULL id the dedup path is not merely unused — it is *disabled
by construction*.

**This is deliberate, and ADR-019 says why.** ADR-019 §2 of the Decision:

> The local SMTP adapter has no such identifier: **each completed `DATA`
> transaction is a distinct event by definition.**

and its Context, case 2:

> **SMTP-level retries**: a sender MTA retries only when it did not receive our
> `250 OK`. If we responded `4xx` (persistence failed), nothing was recorded, so
> the retry is simply the first successful delivery — **no dedup needed.**

A `451` rolls the transaction back. Nothing was committed. The retry is the
first *successful* delivery, not a duplicate of a stored one. **There is no gap
here to close.**

**And stamping a `Message-ID` would violate ADR-019's central decision.** ADR-019
exists because content-based collapsing hides duplicate-send defects in the
system under test, and it names the exact failure:

> the two messages may be byte-identical (same template, same second, or a
> **buggy fixed `Message-ID`**)

A SUT that emits a fixed or repeated `Message-ID` is *precisely* the defect class
a customer buys TestInbox to catch. Make `Message-ID` the dedup key and TestInbox
silently collapses those sends into one row — the customer's bug becomes
invisible, and it becomes invisible *because* of a mechanism we added for our own
retry convenience. That is the single worst outcome available in this design
space.

It is also **attacker-controlled**. Postfix only adds a `Message-ID` when one is
absent, so a sender who supplies one chooses our dedup key. Anyone who knows an
inbox address can pre-bind a `Message-ID` and suppress a later legitimate message
to that inbox for as long as the key survives. That converts a QA tool into one
whose results an outsider can silently alter.

**Judgement: do not stamp `Message-ID`. Do not use `Message-ID` as a dedup key
at all.** The concrete mechanism is in §3.

### 2.3 The strongest argument against SES is missing from the brief

The brief's threat table places "Unknown-recipient content liability" under
INGESTION — "discard in-process, never store (ADR-025)" — with no per-provider
distinction, as though the property holds either way.

**It does not hold under SES, and cannot.**

ADR-025 is unambiguous:

> message content is **discarded immediately in the gateway process** — never
> written to object storage, the database, or any quarantine area.

Under SES, the S3 action is what makes raw MIME available at all; SNS caps at
256 KB, so S3 is *required*, as the brief itself establishes. SES receipt rules
match on recipient *conditions* — they cannot consult Postgres — so for
TestInbox's generated/wildcard addressing the rule must match the **domain**,
accepting every local-part. Therefore:

> **Every message to every non-existent address is written to our S3 bucket,
> under our account, before TestInbox has any opportunity to decide anything.**

The best available mitigation is "delete it quickly after the fact", which is
*persisted-then-deleted*, not *never persisted*. ADR-025's guarantee is
structurally unavailable under any accept-then-store provider.

**This is not merely an ADR violation. It re-opens a compliance decision that
ADR-025 was used to narrow.** VISION.md §2 of the Human Decisions list reads:

> **Legal/compliance posture for receiving arbitrary third-party email** […]
> **Narrowed by ADR-025: unknown-recipient content is never stored, so this
> decision now covers only mail attributed to a tenant's inbox.**

Choosing SES silently un-narrows that decision: the owner would now be deciding
the retention, jurisdiction, deletion and unlawful-content posture for **mail
from strangers to addresses that do not exist**, which is the largest and least
defensible category of the lot — spam, misdirected mail and abuse traffic, all
retained in a bucket we own. The compliance checklist in the brief §10 was drawn
up against the narrowed question and would need to be re-scoped if SES were
chosen.

**This, not the 451 argument, is the strongest architectural reason to prefer a
relay edge.** It is a permanent structural property, not an operational
inconvenience, and it moves a legal exposure rather than an engineering cost.

### 2.4 The brief's own flip-condition 2 is arguably already met, and it does not flip

Brief §14 condition 2: *"No appetite for a fourth host. If the edge would be
unowned and unpatched, SES is safer than a neglected Internet-facing daemon."*

Brief §11 Q11 establishes: **no on-call rota exists, and alerting is Grafana →
email only.** Q1 establishes no spare host exists and a fourth must be
purchased. The brief then recommends Postfix without addressing whether its own
condition has fired.

I do not think it has fired — `unattended-upgrades` covers the patching half,
and an inbound-only Postfix with no local delivery, no relaying and a short
queue lifetime is a genuinely small daemon — but the brief should have argued
that rather than passed over it. **The "unowned" half is unresolved and is a
real condition on the recommendation**, which is why it appears in my §9 rather
than being waved through.

### 2.5 The option space is framed too narrowly

The brief evaluates "Postfix vs SES" as though the alternative to self-hosting
is AWS specifically, and then scores SES down substantially for requiring a
cloud estate that does not exist (§8, §11 Q12, §14 condition 5).

That reasoning is sound about *SES* but is not sound about *managed inbound
mail*. Providers whose entire product is inbound routing — Mailgun Routes,
Postmark inbound, Cloudflare Email Routing — deliver by webhook or forward, need
no IAM, no bucket policy, no billing estate, and no second control plane. They
neutralise most of what the brief scores against SES.

They do **not** change my recommendation, and the reason is precisely §2.3:
every one of them accepts the message on our behalf and stores it before we see
it, so all of them break ADR-025 exactly as SES does, and several have worse raw
fidelity. But the brief should have named them and dismissed them on that
ground, rather than leaving the impression that "managed" implies "AWS".

**The real axis is not Postfix vs SES. It is: does TestInbox control acceptance,
or does a provider accept on its behalf?** Every managed option sits on the same
side of that line, and that line is where ADR-025 lives.

---

## 3. Dedup identity under Postfix (task §3.1)

**Decision: no provider message identity is required for the Postfix relay path.
`providerMessageId` stays `null`, the dedup index stays disabled for
`local-smtp`-class providers, and no `Message-ID` is stamped, read, or trusted.**

This is not a deferral. It is what ADR-019 already decided, and the Postfix edge
does not disturb the reasoning:

| Situation | Behaviour | Correct? |
|---|---|---|
| Ingestion `451`s (schema, storage, DB) | Transaction rolled back, nothing committed. Postfix retries the queued message. The retry is the **first successful delivery**. | Yes — ADR-019 Context case 2 |
| Two genuinely separate sends, byte-identical | Two rows, second annotated `possibleDuplicateOfMessageId` | Yes — ADR-019 Decision 4 |
| Commit succeeds, `250` lost before reaching the edge | Postfix retries; a second row appears | **Deliberate.** ADR-019 Decision 3: "presenting both rows is the faithful outcome" |

The only thing the edge changes is the *width* of that third window: a WireGuard
hop can drop a `250` slightly more readily than loopback can. It widens an
accepted window; it does not create a new class of duplicate. ADR-019 chose to
surface that window rather than risk suppressing a real duplicate-send, and
nothing about a relay hop changes that trade.

**How this interacts with ADR-019:** it is ADR-019, unmodified. The content
fingerprint continues to be computed on every message and stored as
`possibleDuplicateOfMessageId` annotation only. No suppression key is
introduced. This is the whole point: the Postfix path needs no dedup mechanism
*because* it generates no case-1 duplicates.

**Contrast with SES, stated honestly.** The brief calls SES "cleanly ahead" on
identity. It is ahead at solving a problem only it has. SNS/SQS is at-least-once,
so the *same provider event* is genuinely re-presented — ADR-019 Context case 1,
the case dedup exists for, and the case ADR-019 names SES for by name. SES needs
`messageId` because SES creates redeliveries; Postfix needs nothing because it
does not. This is not an axis on which SES is better; it is an axis on which SES
has a requirement Postfix lacks, and meets it.

### 3.1 Contingency mechanism, if the residual window is ever judged unacceptable

Recorded so the option is not lost, and so that if someone later decides the
commit-then-lost-`250` window must be closed, they do not reach for
`Message-ID`. **This is not proposed for adoption now.**

Use an **edge-assigned transport identity**, taken from the trusted edge's own
`Received:` header:

- Postfix writes `Received: … by <edge> (Postfix) with ESMTPS id <QUEUEID>` into
  the queue file **at receipt time**. The retry re-sends those same bytes, so the
  token is stable across delivery attempts — more stable than the live queue id,
  which is the brief's stated objection to using queue ids at all.
- Ingestion reads the **topmost** `Received:` header only, and only accepts it if
  it names the trusted edge; everything below it is sender-controlled and is
  ignored. `providerMessageId` becomes that token; `provider` becomes the edge
  identity rather than `local-smtp`.
- If the top header is absent or foreign, `451` — that is a misconfigured relay,
  and failing loudly is correct.

Properties that make this the only acceptable shape: the identity is **assigned
by us**, not by the sender, so it cannot be used to suppress; it is **transport
metadata**, not content, so ADR-019's no-content-suppression rule is untouched;
and it adds **no header that a SUT could be responsible for**, so the §4 fidelity
argument is not violated the way stamping `Message-ID` would violate it.

It still costs something: two byte-identical sends relayed through the edge get
different queue ids and remain two rows (correct), but a *genuine* duplicate-send
that the edge happens to coalesce would not. Postfix does not coalesce, so this
is theoretical — but it is why this stays a contingency rather than a default.

---

## 4. Raw MIME fidelity vs the product promise (task §3.2)

**Judgement: provider header injection is acceptable. Header *synthesis* is
disqualifying. The distinction is what matters, and neither analysis of it in the
Ops brief draws it.**

The brief treats fidelity as a spectrum from "byte-exact" (Postfix, modulo one
`Received:`) to "degraded" (SES, `X-SES-*`). That framing is not quite right,
because **byte-exact is unavailable under every option including Postfix** — any
relay adds `Received:`, and ADR-005's guarantee is that we store what arrived
without rewriting it, not that what arrived equals what was emitted.

The product promise in PRODUCT.md/VISION.md is showing a tester what their system
under test actually emitted. Against *that*, the test is not "were bytes added?"
but **"could an added byte be mistaken for something the SUT did, or mask
something the SUT failed to do?"**

| Addition | Mistakable for SUT behaviour? | Verdict |
|---|---|---|
| `Received:` (Postfix, and every hop) | No. Universally understood as transport metadata; a tester reading mail headers expects a chain of them. | Acceptable |
| `X-SES-Receipt`, `X-SES-Spam-Verdict`, `X-SES-Virus-Verdict` | No. Provider-namespaced `X-` headers that no SUT would emit. | Acceptable — a small annoyance, not a fidelity failure |
| `Authentication-Results` (SES) | **Marginal.** A real header with real meaning that a receiving system legitimately sets. A tester debugging their own DKIM/SPF could misread ours as theirs. | Tolerable with documentation; the weakest of SES's additions |
| **`Message-ID` stamped when absent** (the Ops brief's own proposal) | **Yes, squarely.** `Message-ID` is the *sender's* responsibility. Its absence is a real, testable SUT defect. Stamping one makes a broken SUT look correct. | **Disqualifying** |

So the fidelity argument, applied consistently, produces a result the brief did
not intend: **SES's header injection is a minor cost, and the brief's own
proposed Postfix mechanism is a major one.** It is the only change on this list
that would cause TestInbox to report a passing result for a genuinely defective
SUT.

**Conclusion:** fidelity is a *small* advantage to Postfix (one expected
transport header vs. four additions, one of which is marginal). It is nowhere
near disqualifying for SES, and it is not the reason to choose Postfix. I score
it accordingly in §7 rather than letting it carry weight it has not earned.

---

## 5. CI parity (task §3.3)

**Judgement: the claim is true, materially narrower than presented, and
improvable on both sides — which makes it a weaker discriminator than the brief's
§13 reason 2 implies.**

**True:** with a Postfix edge, the production ingestion component is
`SmtpGateway`, the same class the ephemeral rehearsal exercises on every
pipeline run. With SES it is an adapter that never meets its provider outside
production. That is a genuine difference and it is worth something in a project
whose quality rests on gates that actually fire.

**Narrower than presented, for two reasons:**

1. **The edge itself is not rehearsed either way.** "The production path is the
   CI-rehearsed path" is true of the second hop and false of the first. Postfix
   configuration, the relay hop, `maximal_queue_lifetime`, the tunnel and the
   `Received:` chain are all production-only under the brief's proposal — and
   the edge is exactly where the new operational risk lives. The parity argument
   as stated compares our half to AWS's half and declares a win.
2. **It is cheap to fix, and should be a condition rather than a bonus.** The
   repo already stands up an ephemeral stack with a synthetic suite in CI
   (`deploy/synthetic/`, `scripts/staging-rehearsal.sh`). Adding a Postfix
   container that relays into the ingestion listener is a small amount of work
   and would make the *whole* path rehearsed — genuinely earning the claim the
   brief already makes. I make this a condition in §9.

**And SES is more rehearsable than the brief allows.** Recorded SNS/SQS event
fixtures plus LocalStack for S3/SQS would exercise the adapter, the S3 fetch,
the at-least-once redelivery path and the dedup-on-`messageId` behaviour — which
is most of the adapter's risk surface. What that cannot cover is SES's *receipt*
behaviour: the exact headers it injects, how receipt rules treat unusual
local-parts, its size limit. Those are provider-behaviour risks, and they are
real, but they are a smaller residue than "an adapter that only meets its
provider in production" suggests.

**Net:** parity favours Postfix. It is worth roughly half of what the brief's
§13 places on it, and only if the edge is brought into the rehearsal — otherwise
the project trades a rehearsed adapter for an unrehearsed daemon and calls it
parity.

---

## 6. Control placement: confirmations, corrections, extensions (task §3.4)

### 6.1 Confirmed against the code

- **Anti-enumeration at ingestion.** `SmtpGateway.data` returns a uniform `250`
  regardless of recipient resolution; `ReceiveInboundDelivery` discards unknown
  recipients in-process with metadata-only logging (hashed recipient token,
  sender domain, size). Confirmed.
- **Rate limiting must not change the SMTP reply.** Confirmed and stronger than
  the brief states: rate-limited recipients are discarded in-process on the same
  uniform-`250` path, with a comment naming the enumeration oracle as the reason
  (`ReceiveInboundDelivery.kt`, INGEST budget block). ADR-027 §1 holds in code.
- **No decompression.** No zip/gzip/archive handling anywhere in
  `backend/ingestion/src/main`. Confirmed — the control is the absence, as the
  brief says.
- **No URL fetching.** No HTTP client in any backend main source path; the parser
  extracts `links` for display only. Confirmed. SSRF is defended by not fetching.

### 6.2 Corrections and additions

**(a) The edge introduces a *new* enumeration oracle, and it has a specific
name.** The brief says the edge "may only reject on syntax", which is right but
abstract. The concrete footgun is Postfix's `relay_recipient_maps`: the *normal*
configuration for a relay host, recommended everywhere to avoid backscatter, and
it rejects unknown recipients at RCPT with `550`. That is exactly the oracle
ADR-025 removes. It must be explicitly **unset**, and accepting the backscatter
trade-off is the deliberate cost of ADR-025. This belongs in the edge
configuration as a commented prohibition, not as tribal knowledge.

**(b) A timing side-channel survives the uniform `250`, and no document mentions
it.** A resolved recipient costs a blob write plus a database transaction; an
unknown recipient costs a lookup and a log line. The reply is identical; the
*latency* is not. Over internet SMTP this is noisy enough to be a weak oracle,
and I do not propose defending it — but it should be **stated as a known
residual** rather than left for someone to discover and treat as a break of
ADR-025. Recommend recording it in `docs/security/abuse-model.md`.

**(c) Attachment extraction is a storage amplifier, and it is bounded — say by
what.** The parser extracts each attachment into its own object before the row
is written. One accepted message therefore produces `1 + n` objects. This is not
an archive bomb (nothing is decompressed) and it is bounded, because attachments
are transfer-encoded within the raw message and `maxRawSizeBytes` caps the whole
transaction — decoded output is roughly ¾ of the encoded input. The bound exists;
it is worth writing down so nobody later adds server-side decompression
believing the size check covers them.

**(d) Under SES, unknown-recipient discard cannot be delegated at all** — §2.3.
The brief's table implies this control sits at ingestion for both options. It
does not exist for SES.

### 6.3 Controls that cannot be delegated to a provider or an edge

Stated explicitly, as the task asks:

| Control | Why it cannot move |
|---|---|
| **Uniform `250` / no existence oracle** (ADR-025) | Only ingestion knows whether an inbox exists. Any component that answers earlier either does not know, or knows and leaks. |
| **Discard-not-store for unknown recipients** (ADR-025) | Requires the existence decision *before* anything durable is written. Structurally impossible for accept-then-store providers. |
| **No content-based suppression** (ADR-019) | A provider that dedups by `Message-ID` or body hash hides SUT defects. Must be off, and cannot be re-implemented at the edge. |
| **One event = one atomic multi-recipient commit** (ADR-026) | A database transaction property. No edge or provider can supply it. |
| **Raw-before-row ordering** (ADR-005) | Ordering inside our own write path. |
| **Workspace-scoped rate limits and quotas** (ADR-027) | Keyed on tenancy the edge deliberately does not know. The edge must not be given workspace identity — that is the point of it holding no application state. |
| **HTML rendering isolation** (ADR-011) | Read-side. Unaffected by provider choice, and worth stating so it is not traded away as "the provider scans for malware". |

Provider AV/spam verdicts (SES) are **additive signal only**. They must never
gate storage: a message SES marks as spam is still what the SUT emitted, and
suppressing it would be ADR-019's error wearing a different hat.

### 6.4 Additions to the compliance checklist (application-specific)

The brief's §10 is sound and I do not restate it. Three product/application items
it does not cover, kept strictly separate from anything legal:

| # | Item | Why it is application-specific |
|---|---|---|
| 11 | **Does the provider choice change the *scope* of decision §9?** | Yes, and only the application side can see it: VISION.md narrowed the third-party-mail decision on ADR-025's never-stored guarantee. An accept-then-store provider re-widens it to include mail to non-existent addresses. **The owner must be told which question they are being asked before they answer it.** |
| 12 | **Provider spam/virus verdicts are retained content annotations.** | If SES verdicts are stored, we are recording a third party's judgement about a customer's mail. Harmless, but it is data we would not otherwise hold and it should be a conscious inclusion. |
| 13 | **`possibleDuplicateOfMessageId` links messages across senders within one inbox.** | Existing behaviour, not provider-specific, but it is a content-derived relationship stored about third-party mail and belongs on the retention list rather than being discovered later. |

---

## 7. Independent weighted matrix

Weights are fixed and justified **before** scoring, and are mine — deliberately
not the brief's criteria, so that agreement (or not) means something. Scores are
1–5.

### 7.1 Weights, justified before scoring

| # | Criterion | Weight | Justification |
|---|---|---|---|
| 1 | **Preservation of Accepted-ADR invariants** | 22 | The highest, because CLAUDE.md makes Accepted ADRs authoritative over code. An option that structurally cannot satisfy one is not making a trade — it is requiring a superseding ADR and a re-opened human decision. That is a different and larger act than "scores lower on architecture fit". |
| 2 | **Delivery-semantics safety** (no silent drop *and* no silent duplicate) | 18 | The product is a QA oracle. Both failure directions corrupt the answer it exists to give, and they are not interchangeable: a drop fails the test, a duplicate fabricates SUT behaviour. |
| 3 | **Operational risk given *this* estate** | 15 | Weighted on the §1 facts as they are — no spare host, no on-call, email-only alerting — not on an idealised team. Both options add an unowned thing. |
| 4 | **Reliability and buffering** | 12 | Real and user-visible, but bounded: inbox TTLs are minutes (ADR-009), so a message buffered for hours is already worthless. This is why it is not weighted like a general-purpose mail system. |
| 5 | **Testability of the production path** | 10 | This project's quality demonstrably comes from gates that fire. Weighted meaningfully, but below correctness, and discounted because it is improvable on both sides (§5). |
| 6 | **Security surface** | 8 | Both are defensible; the trade is an open port-25 daemon against static cloud credentials on the production host plus a bucket of third-party mail. Neither dominates. |
| 7 | **Provider dependence of the intake path** | 6 | ADR-003's port means the *code* is not locked in either way. The lock-in is a DNS/MX migration — disruptive but recoverable, and not a permanent property. |
| 8 | **Time to production** | 6 | Real but recoverable, and TI-004 is explicitly blocked on a human decision anyway, so schedule pressure should not buy much here. |
| 9 | **Cost** | 3 | Negligible either way at expected volume. Included only so its irrelevance is on the record. |
| | | **100** | |

### 7.2 Scoring

| # | Criterion | W | Postfix | SES | P×W | S×W | Note |
|---|---|---|---|---|---|---|---|
| 1 | ADR invariants | 22 | 5 | 2 | 110 | 44 | Postfix preserves all. SES structurally breaks ADR-025 (§2.3) and degrades ADR-005 |
| 2 | Delivery safety | 18 | 4 | 4 | 72 | 72 | **Genuine tie.** Postfix: no drop, accepted duplicate window (ADR-019). SES: no drop, dedup by `messageId`, liveness depends on a queue nobody watches |
| 3 | Operational risk here | 15 | 2 | 3 | 30 | 45 | Postfix: fourth host, internet-facing daemon, no on-call. SES: no daemon, but an entire cloud estate, IAM and billing owner from zero |
| 4 | Reliability | 12 | 3 | 5 | 36 | 60 | S3+SQS is stronger and needs no operation. Discounted by weight, not by score |
| 5 | Testability | 10 | 4 | 2 | 40 | 20 | Postfix reuses the rehearsed `SmtpGateway` and the edge *can* join the rehearsal; SES's receipt behaviour never can |
| 6 | Security surface | 8 | 3 | 4 | 24 | 32 | SES removes port 25; adds static AWS creds on OVH and a bucket of strangers' mail |
| 7 | Provider dependence | 6 | 5 | 2 | 30 | 12 | SES owns the MX — the intake path is the worst thing to migrate |
| 8 | Time to production | 6 | 3 | 2 | 18 | 12 | Buy+configure a host, vs. create an AWS account, IAM, rules and an adapter from nothing |
| 9 | Cost | 3 | 4 | 4 | 12 | 12 | Negligible both ways |
| | **Total** | **100** | | | **372** | **309** | |
| | **Normalised (÷5)** | | | | **74.4** | **61.8** | |

### 7.3 Comparison with the Ops matrix — after scoring, as instructed

Ops: **69 vs 67**, reported as a tie inside its own noise. Mine: **74 vs 62** —
a clearer margin.

The divergence is almost entirely **criterion 1**, which the Ops matrix does not
have. Its nearest equivalent, "Fit with TestInbox architecture" (weight 15,
5 vs 2), is scored on the 451/parity/fidelity arguments — the ones I find
overweighted — and does not include the ADR-025 finding at all. Add that finding
and raise the weight to reflect that Accepted ADRs are authoritative, and the
result stops being a tie.

**I therefore disagree with the brief's own reading of its result.** It concludes
"the decision is not determined by the criteria". I think it is determined — just
not by the criteria the brief chose. On my weighting the margin is ~12 points and
survives moving any single weight by one class; it does not survive deleting
criterion 1, which is precisely why criterion 1 needs to be argued rather than
assumed. §2.3 is that argument.

---

## 8. Recommendation, independently reached

**Recommend: self-hosted Postfix relay edge on a dedicated host — conditional.
SES (and managed inbound generally) retained as a pre-analysed fallback, not a
rejected option.**

Same direction as Ops. Different reasons, in priority order:

1. **ADR-025 is unavailable under any accept-then-store provider, and its loss
   re-opens a human decision that VISION.md records as narrowed.** This is the
   decisive argument. It is structural, permanent, and it converts an
   architecture choice into a legal-scope change the owner has not been asked
   about. (§2.3)
2. **The rehearsed-path advantage is real, conditional on actually rehearsing the
   edge.** Worth having; not worth claiming until the edge is in CI. (§5)
3. **The intake path is the worst thing in the system to migrate**, and Postfix
   keeps it portable. (§7 criterion 7)
4. **The usual self-hosted-mail objection is about sending and does not apply.**
   Agreeing with the brief here, because it is correct.

Explicitly **not** among my reasons: the 451/sender-owned-retry argument, which
is true but roughly half as load-bearing as presented (§2.1), and raw-MIME
fidelity, which is a minor advantage that the brief's own proposed mechanism
would have squandered (§4).

**This recommendation is not actionable until:**

- the VISION.md §2 compliance decision is **approved** — both options are NO-GO
  without it, and the owner must be told (per §6.4 item 11) that choosing an
  accept-then-store provider changes the question being asked;
- Ops confirms a dedicated edge host, inbound port 25, and rDNS control
  (brief §11 Q1–Q3, all open);
- an **owner is named** for the edge as an operational class (§2.4);
- the edge is **added to the ephemeral CI rehearsal** before it carries
  production mail (§5).

The dedup design the brief lists as a blocking condition is **not** a condition:
it is already decided by ADR-019 and requires no new mechanism (§3).

---

## 9. Conditions that would change my recommendation

Any one of these flips me to a managed provider:

1. **No inbound port 25 or no rDNS control** at any available provider. Fatal, as
   the brief says.
2. **No named owner for the edge.** The brief lists this and then does not apply
   it; I would. An unowned internet-facing daemon is worse than a lock-in, and
   §1 establishes there is no on-call today. This is the condition most likely to
   actually fire.
3. **The owner decides to accept the wider compliance scope** — i.e. answers
   VISION.md §2 in a way that permits storing mail to non-existent addresses.
   That deletes my primary argument (§2.3), and with criterion 1 neutralised the
   matrix is close to the tie Ops reported.
4. **A hard availability commitment** (an SLA), where a single spool is not
   enough and running two edges is unwanted.
5. **Legal requires a named processor** with contractual guarantees for
   third-party content.
6. **Sustained abuse volume** beyond what one edge and one part-time owner absorb.

Deliberately **not** on my list, though it is on the brief's: *"an AWS account
already exists"*. That lowers SES's setup cost; it does nothing about ADR-025.
Under my weighting it is not close to decisive, which is a direct disagreement
with the brief calling it "the single biggest scoring swing".

And one that would flip **back** to Postfix even from a chosen managed provider:
a requirement that unknown-recipient mail never be persisted — i.e. ADR-025
being reaffirmed rather than superseded.

---

## 10. Proposed ADR-004 text

> Mine, not a copy of the Ops draft. Supplied for the owner to adopt.
>
> **Outcome:** adopted in substance on 2026-09-08. The accepted wording lives in
> [ADR-004](0004-initial-inbound-provider-strategy.md) and differs from the draft
> below — it carries the owner's product boundary, the EU residency requirement,
> the six preconditions on a public MX, and the DNS/backup constraints, none of
> which this draft could have known. The draft is left as written.

```markdown
# ADR-004: Inbound Provider Strategy

**Status:** Proposed — blocked on the VISION.md §2 human decision and on the
conditions in the TI-004 analyses (Ops brief and application analysis).

## Context

MVP runs entirely locally through the SMTP adapter, which is unchanged by this
decision. Production needs a provider for internet-originated mail.

The application's inbound contract is SMTP-shaped and is expressed in code:
one DATA transaction is one event, committed atomically across all its
recipients (ADR-026); infrastructure failure soft-fails 451 so the sending MTA
owns the retry; raw MIME is stored before parsing (ADR-005); each completed DATA
transaction is a distinct event and is never suppressed by content (ADR-019);
unknown recipients receive a uniform 250 and their content is discarded
in-process, never written to storage, the database or any quarantine (ADR-025).

Providers divide on one axis, and it is not "self-hosted vs cloud": **either
TestInbox controls acceptance, or a provider accepts on its behalf and stores
the message before TestInbox sees it.** AWS SES, Mailgun Routes, Postmark
inbound and Cloudflare Email Routing are all on the far side of that line.

## Decision (proposed)

Adopt a **self-hosted Postfix relay edge on a dedicated host**, relaying over an
authenticated private channel (WireGuard initially, mTLS as hardening) to the
existing ingestion listener.

The edge holds no application state, no credentials, and no route to the
application, database or object-storage networks. It rejects on RCPT **syntax
only** — `relay_recipient_maps` must remain unset, because recipient
verification at the edge rebuilds the enumeration oracle ADR-025 removes, and
the resulting backscatter is the accepted cost of that ADR.

Public SMTP is NOT colocated with the production application stack.

No provider message identity is introduced for this path. `providerMessageId`
remains null for SMTP-class providers and the dedup index remains inactive for
them, per ADR-019: after a 451 nothing was committed, so a retry is the first
successful delivery. No `Message-ID` is stamped, read or trusted as an identity.

Accept-then-store managed providers are retained as a **pre-analysed fallback**.
Adopting one requires superseding ADR-025 and re-opening VISION.md §2, because
mail to non-existent addresses would then be written to storage we own before
TestInbox can decide anything. That is an owner decision, not an implementation
detail.

**Conditions before this may be marked Accepted:**

1. VISION.md §2 approved;
2. Ops confirms a dedicated edge host, inbound port 25 and rDNS control;
3. a named owner for the edge as an operational class;
4. the edge is exercised by the ephemeral CI rehearsal, so the production path
   is rehearsed end to end rather than from the relay hop inward.

## Alternatives considered

- **AWS SES receiving.** Stronger buffering, no port-25 exposure, provider
  message identity that makes at-least-once redelivery safe, and it scales
  without us. Rejected as the default because SES accepts and writes the message
  to S3 before TestInbox is consulted, so ADR-025's "never written to object
  storage" cannot hold for unknown recipients — which re-widens the VISION.md §2
  compliance decision to cover mail from strangers to addresses that never
  existed. Secondary costs: X-SES-* and Authentication-Results headers in the
  stored artifact, a production adapter whose provider behaviour CI cannot
  rehearse, static AWS credentials on the production host, and an MX pointed at a
  provider the estate does not otherwise use.
- **Managed inbound routing (Mailgun, Postmark, Cloudflare Email Routing).**
  Avoids SES's cloud-estate cost — no IAM, no bucket policy, no billing estate.
  Rejected for the same structural reason as SES: all accept on our behalf and
  store before we see it.
- **Public SMTP colocated on the production host.** Rejected: an
  unauthenticated, internet-facing, attacker-reachable parser on the same host as
  unrelated production applications and secret services.
- **Edge-stamped Message-ID as a dedup identity** (proposed by the Ops brief).
  Rejected as unsafe: ADR-019 names a "buggy fixed Message-ID" as a SUT defect
  TestInbox exists to reveal, so keying dedup on that header would silently
  collapse exactly the duplicate-send defects the product is bought to catch.
  It is also sender-controlled, letting an outsider who knows an inbox address
  pre-bind a key and suppress a later legitimate message. If the
  commit-then-lost-250 window is ever judged unacceptable, the identity must be
  edge-assigned transport metadata (the trusted edge's own Received id), never a
  sender-supplied header.

## Consequences

- A dedicated edge host must be provisioned, firewalled, patched, monitored and
  **owned**. This is a new operational class for an estate with no on-call rota.
- The production ingestion component is the SMTP adapter CI already rehearses;
  condition 4 extends that to the relay hop.
- Raw MIME stays faithful apart from the Received: header every hop adds.
- ADR-019 and ADR-025 hold unchanged. No new dedup mechanism is introduced.
- Backscatter to forged senders is accepted, deliberately, as the cost of
  ADR-025.
- Migration to a managed provider later is an adapter plus a DNS change —
  ADR-003's port keeps the application indifferent — but it is not purely
  technical, because it requires superseding ADR-025.
```

---

## 11. What the Ops brief got wrong

Collected, with severity. The brief is good and most of this is correction
rather than contradiction — but three items are load-bearing.

| # | Item | Severity | Detail |
|---|---|---|---|
| 1 | **"`providerMessageId` is what stops a 4xx retry becoming a duplicate"** | **Material** | Nothing stops it and nothing needs to. `SmtpGateway` passes null; the index is partial on `IS NOT NULL`; ADR-019 decided a post-451 retry is the *first successful delivery*. §2.2 |
| 2 | **"The edge stamping a `Message-ID` when absent is the correct answer"** | **Material — do not implement** | Would key dedup on a sender-controlled header that ADR-019 explicitly names as a SUT defect signal, hiding duplicate-send bugs and enabling third-party suppression. §2.2 |
| 3 | **Unknown-recipient discard shown as an ingestion control for both options** | **Material — and it is the strongest argument the brief had** | ADR-025 cannot hold under SES; the S3 write precedes us. It also re-widens VISION.md §2. §2.3 |
| 4 | The 451/sender-retry argument carries the recommendation | Overweighted | Real, but SQS redelivery is configuration rather than a retry contract we build, and what actually changes owner is liveness/observability. §2.1 |
| 5 | Flip-condition 2 ("unowned edge") is listed but never tested against §11 Q11's "no on-call exists" | Internal inconsistency | The brief establishes the fact and does not apply its own condition. §2.4 |
| 6 | "SES is cleanly ahead" on message identity | Misleading framing | SES needs `messageId` because SNS/SQS creates redeliveries; Postfix needs none because it does not. Meeting a requirement you alone have is not an advantage. §3 |
| 7 | Option space presented as Postfix vs SES | Incomplete | Managed inbound ≠ AWS. Mailgun/Postmark/Cloudflare avoid the cloud-estate cost the brief scores heavily against SES — and lose to the same ADR-025 argument, which the brief did not make. §2.5 |
| 8 | Matrix read as "not determined by the criteria" | Disagree | Determined once invariant preservation is a criterion. 74 vs 62 on my weighting. §7.3 |
| 9 | "Byte fidelity: Postfix … adds a `Received:` header and nothing else" vs SES "fidelity loss" | Imprecise | Byte-exactness is unavailable under every relay. The useful test is whether an addition is mistakable for SUT behaviour — by which SES's headers are minor and the brief's own `Message-ID` proposal is disqualifying. §4 |
| 10 | "The production adapter is the adapter CI rehearses" | Half true | True of the ingestion hop, false of the edge, which is where the new risk is. Fixable, and should be a condition. §5 |
| 11 | Edge "may only reject on syntax" | Correct but under-specified | The concrete footgun is `relay_recipient_maps`, the *normal* relay configuration. Must be named and prohibited. §6.2(a) |
| 12 | Timing side-channel not mentioned | Omission (minor) | Uniform `250`, non-uniform latency. Weak over internet SMTP; should be recorded as a known residual. §6.2(b) |

**Corrections to the brief that I verified and agree with:** its own §1 correction
about `unattended-upgrades`, and its §11 Q4 method note that a public resolver
returning `127.255.255.254` is Spamhaus's query-refused code rather than a
listing. Both are the kind of self-correction that makes the rest of the document
trustworthy.

---

## What was NOT done

*As written, before the owner decision:* no provider implemented, no AWS
resource created, no MX or DNS record created, no production deployment, no
ingestion or idempotency semantics changed, no legal advice given.

**Still true after the decision.** ADR-004 is now Accepted on this branch, and
the ADR is explicit that it decides architecture only: it authorises no
production deployment, no public MX, and no DNS. Six preconditions remain open,
including a named human owner for the edge and the relay hop joining the
automated rehearsal.

The point this analysis asked the owner to notice — that an accept-then-store
provider would widen the scope of the VISION.md §2 question (§6.4 item 11) — was
answered directly: ADR-025 is kept, not superseded, and adopting such a provider
later needs a fresh owner decision and an ADR supersession.
