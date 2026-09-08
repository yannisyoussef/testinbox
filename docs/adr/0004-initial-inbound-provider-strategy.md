# ADR-004: Initial Inbound Provider Strategy

**Status:** Accepted (2026-09-08). Replaces the Proposed form of this ADR, which
deferred the production provider choice pending the human decision recorded in
`VISION.md`. That decision has now been made. The original Proposed text is
retained verbatim under [History](#history), because the deferral was correct
and its stated conditions are what the decision was finally made against.

> **Owner decision, 2026-09-08.** Internet-originated third-party mail is
> approved **in principle** within the existing product boundary. The initial
> production inbound provider is a **dedicated self-hosted Postfix relay edge**.
> **ADR-025 is kept and is not weakened.** Managed inbound providers remain a
> documented fallback, not a rejected option.
>
> This ADR records an architecture decision. It is **not** legal advice, and it
> does **not** authorise production deployment or a public MX — see
> [Gates](#gates-that-remain-closed).

## Context

The MVP had to run entirely locally, so this ADR originally chose "build the
local SMTP adapter first, defer the production provider". That deferral has been
discharged: the local adapter exists and is the path CI rehearses, and the
production choice has now been analysed and decided.

Two independent analyses were produced and reviewed together:

- `docs/adr/0004-inbound-provider-decision-brief.md` — Ops, from the
  infrastructure side ([PR #23](https://github.com/yannisyoussef/testinbox/pull/23));
- [`0004-inbound-provider-application-analysis.md`](0004-inbound-provider-application-analysis.md)
  — application side, written against the code and the product promise.

They converge on the recommendation and were reconciled in the Ops brief's
errata. **Both are retained.** They are the reasoning this decision was made
against, and the conditions under which it should be revisited live in them as
much as here.

The decisive constraint is not "self-hosted versus cloud". It is:

> **Either TestInbox controls acceptance, or a provider accepts on its behalf
> and stores the message before TestInbox sees it.**

[ADR-025](0025-unknown-recipient-handling.md) requires that content addressed to
an unknown inbox is *"discarded immediately in the gateway process — never
written to object storage, the database, or any quarantine area."* An
accept-then-store provider cannot satisfy that: the durable write precedes any
decision we could make. Every managed inbound option evaluated — AWS SES,
Mailgun Routes, Postmark inbound, Cloudflare Email Routing — sits on the far side
of that line. That is what selects a relay edge, and it is why the owner
decision keeps ADR-025 rather than superseding it.

## Decision

**1. Provider.** The initial production inbound provider is a **dedicated
self-hosted Postfix relay edge**, relaying over an authenticated private channel
to the existing ingestion listener. The application-side adapter is unchanged:
production ingestion is the `SmtpGateway` that CI already exercises.

**2. ADR-025 is not superseded.** The unknown-recipient invariant stands as
written. The provider choice is *constrained by* it, not the other way round.
Adopting a provider whose acceptance semantics conflict with ADR-025 requires a
**new explicit owner decision and an ADR supersession** — it is not an
implementation detail and must not be reached by drift.

**3. Topology.** Public SMTP is **not** colocated with the production
application stack:

```
Internet → dedicated EU SMTP edge (Postfix) → authenticated private relay
        → TestInbox production ingestion (OVH)
```

The edge carries the minimum state and privilege needed to receive and relay.
It must **not** have access to the TestInbox database, object-storage
administration, unrelated OVH services, staging networks, or Docker control
sockets. Contabo US (staging), the Contabo EU privileged CI runner, and the
shared OVH application host are all **excluded** as the public edge.

**4. Recipient handling at the edge is syntax-only.** The edge must not perform
recipient existence checks. Concretely, Postfix's `relay_recipient_maps` must
remain **unset**: it is the normal configuration for a relay host and it rejects
unknown recipients at RCPT, which rebuilds precisely the enumeration oracle
ADR-025 removes. The resulting backscatter is the accepted, deliberate cost of
ADR-025.

**5. No provider message identity is introduced for this path.**
`providerMessageId` remains `null` for SMTP-class providers and the partial
uniqueness index stays inactive for them, per
[ADR-019](0019-inbound-deduplication-semantics.md): after a `451` nothing was
committed, so a retry is the *first successful delivery*, not a duplicate. No
`Message-ID` is stamped, read, or trusted as an identity — see
[Alternatives](#alternatives-considered).

**6. Data residency.** Initial production mail processing and storage remain in
the EU: the edge in the EU, the application and data plane on OVH France.
Production raw MIME is not routed through the US staging host.

**7. Managed inbound providers remain a documented fallback**, pre-analysed and
not rejected. The conditions that would select one are recorded below and in
both analyses.

### Gates that remain closed

This ADR decides the architecture. It does **not** authorise production, and the
following are preconditions on enabling a public MX rather than consequences of
this decision:

| # | Gate | Owner |
|---|---|---|
| 1 | A **named human operational owner** for the edge, with runbooks covering patching, queue/spool growth, disk pressure, abuse reports, provider incidents, SMTP flood/DoS, emergency disablement and CVE response. No public Internet daemon without an assigned owner. | Owner + Ops |
| 2 | Provider confirmation of inbound TCP/25, stable public IPv4, PTR/rDNS control, firewall control, and terms compatible with inbound mail infrastructure — **before** provisioning. | Ops |
| 3 | The **Postfix relay hop is part of the automated rehearsal**, proving end to end: active recipient accepted; nonexistent recipient body never persisted; multi-recipient behaviour; downstream `451`/retry semantics; message-size limits; connection/recipient abuse limits; successful delivery. | Application |
| 4 | Monitored `abuse@`, `security@` and `postmaster@` operational addresses — these are operational contacts, never tenant inboxes. | Ops |
| 5 | Public-production activation prerequisites: privacy policy, terms, abuse process, retention documentation and appropriate legal review. | Owner + legal |
| 6 | Existing production-readiness blockers, which are independent of this ADR: OVH monitoring, backups, `DOCKER-USER`/origin isolation, storage isolation and quota, production secrets, deployment validation. | Ops |

DNS is not created during design or implementation. When enabled, the initial
naming is `testinbox.email MX 10 mx1.testinbox.email` with
`mx1.testinbox.email A <edge-ip>`, and the SMTP host is **DNS-only, never
Cloudflare HTTP-proxied** — Cloudflare does not proxy SMTP, so proxying it would
break mail rather than protect it.

Raw MIME and attachments remain **deliberately not backed up**, which is what
makes the TTL and deletion semantics of
[ADR-009](0009-retention-lifecycle.md) true. No backup mechanism may silently
retain message content beyond the documented retention window.

## Alternatives considered

- **Managed accept-then-store inbound providers** — AWS SES receiving, Mailgun
  Routes, Postmark inbound, Cloudflare Email Routing. Genuinely stronger on
  buffering (S3/SQS needs no operation from us), remove our port-25 exposure,
  and SES supplies a provider message id that makes at-least-once redelivery
  safe. **Rejected as the initial provider** because all of them accept and
  durably store the message before TestInbox is consulted, so ADR-025's
  never-stored guarantee cannot hold for unknown recipients — and because
  `VISION.md` records the third-party-mail decision as *narrowed by* that
  guarantee, adopting one would silently widen the question the owner was asked.
  Secondary costs, in descending order: provider headers injected into the
  artifact ADR-005 exists to preserve; a production adapter whose provider
  behaviour CI cannot rehearse; for SES specifically, static cloud credentials
  on the production host and an MX pointed at a provider the estate does not
  otherwise use. Retained as a fallback under the conditions below.
- **Public SMTP colocated on the production host.** Rejected: it places an
  unauthenticated, internet-facing, attacker-reachable parser on the same host
  as unrelated production applications and secret services.
- **Edge-stamped `Message-ID` as a dedup identity.** Proposed in an early
  revision of the Ops brief and **withdrawn** in its errata. Rejected, and
  recorded here so it is not re-proposed: ADR-019 names a *"buggy fixed
  `Message-ID`"* as a system-under-test defect TestInbox exists to reveal, so
  keying suppression on that header would silently collapse exactly the
  duplicate-send bugs the product is bought to catch. It is also
  sender-controlled — Postfix stamps only when the header is absent — so anyone
  who knew an inbox address could pre-bind a key and suppress a later legitimate
  message. If the commit-then-lost-`250` window is ever judged unacceptable, the
  identity must be **edge-assigned transport metadata** (the trusted topmost
  `Received:` id), never a sender-supplied header.

## Conditions that would revisit this decision

Recorded so a future change is a decision rather than a drift. Any one of these
reopens the provider choice:

1. **No inbound port 25, or no rDNS control**, at any acceptable provider.
2. **No named owner for the edge.** An unowned internet-facing daemon is worse
   than provider dependence; this is a precondition above, and its loss is a
   reason to revisit, not to continue.
3. **An explicit owner decision to permit storing unknown-recipient content**,
   which would supersede ADR-025 and remove the argument that selects a relay
   edge.
4. **A hard availability commitment** (an SLA) where a single spool is
   insufficient and running two edges is unwanted.
5. **Legal requires a named processor** with contractual guarantees and audit
   posture for third-party content.
6. **Sustained abuse or volume** beyond what one edge and its owner absorb.

## Consequences

- A dedicated EU edge host must be provisioned, firewalled, patched, monitored
  and **owned**. This is a new operational class for an estate that has no
  on-call rota today, which is why gate 1 exists.
- Production ingestion is the SMTP adapter CI already rehearses; gate 3 extends
  that rehearsal to the relay hop, so the tested path is the production path
  rather than only its second half.
- Raw MIME stays faithful apart from the `Received:` header every hop adds.
  Byte-exactness is unavailable under any relay and was never the promise;
  ADR-005's guarantee is that we store what arrived without rewriting it.
- ADR-019 and ADR-025 hold unchanged. **No new deduplication mechanism is
  introduced by this decision.**
- Backscatter to forged senders is accepted, deliberately, as the cost of
  ADR-025's uniform `250`.
- A residual timing side-channel survives the uniform reply — a resolved
  recipient costs a blob write and a transaction, an unknown one does not. It is
  weak over internet SMTP and is recorded as a known residual rather than
  defended.
- Migration to a managed provider later is an adapter plus a DNS change, and
  [ADR-003](0003-inbound-mail-abstraction.md)'s port keeps the application
  indifferent — but it is not purely technical, because it requires superseding
  ADR-025.
- `VISION.md` human decision 3 (which inbound provider to build first) is
  discharged by this ADR. Decision 2 (legal/compliance posture) is approved **in
  principle** for the product boundary and remains gated for public production
  activation.

## History

This ADR was `Proposed` from the walking skeleton until 2026-09-08. Its original
text is preserved verbatim below. The deferral it recorded was correct: the
choice genuinely depended on operational ownership, which is a business decision,
and it is the decision that has now been made.

> # ADR-004: Initial Inbound Provider Strategy
>
> **Status:** Proposed
>
> ## Context
>
> MVP must run entirely locally (no cloud dependency). Production eventually
> needs a durable inbound provider (self-hosted Postfix vs. AWS SES).
>
> ## Decision (proposed)
>
> Build a local SMTP adapter first (sufficient for MVP and CI, no cloud
> dependency). Defer the choice between self-hosted Postfix and AWS SES for a
> production-grade provider until a target deployment environment is decided
> — this is listed as a Human Decision Required in `VISION.md`, since it
> depends on operational ownership (self-hosted mail infra vs. relying on
> AWS) that is a business decision, not purely technical.
>
> ## Alternatives considered
>
> - **Build SES adapter first**: rejected for MVP — introduces an AWS
>   dependency that contradicts the "runnable entirely locally" MVP
>   requirement, and SES receiving setup (domain verification, SNS/Lambda
>   wiring) is nontrivial ceremony to front-load before the core value
>   proposition is validated.
>
> ## Consequences
>
> - MVP has no path to receiving real internet-originated mail yet — only
>   mail from a system-under-test configured to point at the local adapter.
>   This is acceptable because the primary use case (SUT → TestInbox in a test
>   environment) does not require internet-facing mail receipt.
> - This ADR cannot be marked `Accepted` for the production provider choice
>   until the relevant Human Decision is made.
