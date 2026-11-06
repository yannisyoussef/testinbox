# The Postfix mail edge — application-owned contract

## Correction (2026-11-06): the edge is internet-facing

The status block below ("**dormant**", "No public MX. No public SMTP. No
production relay.") was true when written and is kept as dated history. It was
**superseded on 2026-10-28** by the owner-authorized owner-only beta launch, and
this section corrects it on the owner's statement of 2026-11-06 (#124, comment
6686330363):

> Yes. I previously authorized public inbound SMTP and the MX for
> inbox.testinbox.email as part of the owner-only production beta launch.
>
> The intended state is:
> - Public inbound SMTP through mx1.testinbox.email.
> - Internet senders can deliver to TestInbox recipient addresses.
> - Inbox creation and message access remain authenticated and restricted.
> - Self-service sign-in, participant admission and storage-enforcement
>   activation remain governed by their separate release authorizations.
>
> Keep the existing public delivery path operational. Withdrawing the MX or
> closing public SMTP is not authorized by this documentation discrepancy.

The same statement says it "does not open registration or admission, accept
additional residuals, or waive review of functional changes".

**What is live.** Dates and times are UTC, from Ops' launch records as reported
by Ops on #124 (comment 6686375458):

| step | when | what |
|---|---|---|
| G3, public tcp/25 listener | 2026-10-28 18:07:03Z | edge env `EDGE_PUBLIC_SMTP=true`; smtpd listens on `0.0.0.0:25` |
| G4, public MX | 2026-10-28 18:12:03Z | `inbox.testinbox.email MX 10 mx1.testinbox.email` |

Ops' read-only check of 2026-11-06 (#124, comment 6686107928) also records that
nftables accept inbound tcp/25 and that the edge relays the tenant domain to
production ingestion over the private WireGuard hop (`relay:`), with
`postmaster@` routed `local:` and `default_transport = discard`, as this
contract requires. "No production relay" no longer holds either.

**Authorization and acceptance records.** These live in the Ops workspace
(`/opt/infinity/workspace/ti-008-beta/`) and in `infinity-core`, not in this
repository; they are cited as Ops reported them.

| record | reference |
|---|---|
| authorization | `launch-20261028/LAUNCH-RUNBOOK.md` (sha256 `25dd94da4c1f8188…`, per #124 comment 6686721348) line 3, as quoted by Ops: "**Authorization:** owner GO 2026-10-28, 'complete the owner-only TestInbox beta release today'. Its scope is real internet mail for the owner's own validation. Excluded: public registrations, other testers, unrestricted API/web access, unrelated DNS/firewall/egress/WAF changes, storage enforcement, optional maintenance, hardening extras (CAA, null MX, MTA-STS/TLS-RPT stay follow-ups)." Independent reviews: `launch-20261028/REVIEW-security.md`, `REVIEW-ops.md` |
| result | `launch-20261028/RESULT.md` (sha256 `b80374ae679a6917…`): "RESULT = PASS (owner-only beta left enabled)" |
| closeout | `closeout/PHASE2-CLOSEOUT.md` (sha256 `f5a1067d92a09009…`): acceptance A1–A7 PASS on day 2 (2026-10-29 14:02Z). Owner acceptance was recorded by the ops lead; there is no separate owner-signed file. The owner statement above is the citable owner confirmation |
| evidence index | `closeout/EVIDENCE-INDEX.md` (sha256 `e05ec55b63d17a6f…`) |
| `infinity-core` MRs | !164 (mx1 certificate path), !166 (B2 recipient allow-list), !167 (`edge_public_smtp_intended` gauge); !192 is Ops' own docs correction |

**What did not change.**

- The contract below is unchanged, and every invariant in it now protects a
  live, internet-facing listener rather than a rehearsal. It still requires
  ADR-025's uniform `250` at the edge: `relay_recipient_maps` empty, and an
  unknown recipient relayed and discarded in ingestion.
- Inbox creation and message access stay authenticated. Self-service sign-in,
  participant admission and storage enforcement stay closed under their own
  release authorizations.
- `deploy.sh` still does not deploy the edge; Ops reconciles it from
  `infinity-core`.

**What this correction does not settle.**

- **The app-side gate still encodes "no public SMTP".**
  `scripts/check-mail-edge-contract.sh` refuses the `live` listener in a
  `production` render, `render.sh` warns that "No MX and no public SMTP are
  sanctioned yet", and a comment in `deploy/mail-edge/contract.yaml` says
  CLAUDE.md's "no public SMTP" rests on the listener switch. Ops renders the
  real edge with its own `reconcile.sh` in `infinity-core`, so these do not
  touch the live host. Aligning them is a functional change and goes through
  normal review; this documentation-only correction leaves them as they are.
- **The [known gaps](#known-gaps) are now gaps on a live edge.** They were
  written as "all matter before public SMTP". They are tracked, not closed.
- **Two exposures found after launch**, both addressed by the open draft
  [#147](https://github.com/yannisyoussef/testinbox/pull/147) together with
  Ops' lockstep host change in `infinity-core`:
  - **ETRN.** The contract sets neither ETRN key, so Postfix's defaults leave
    ETRN enabled for the tenant domain: on the rehearsal image
    `ETRN inbox.testinbox.email` answers `250 Queuing started`. Ops confirmed
    the live edge has the same settings and advertises ETRN, so any internet
    client can force an immediate flush of the deferred queue. **Not closed**
    until #147 and the host change land and Ops verifies a `5xx` on the live
    edge.
  - **DSN.** EHLO advertises DSN. #147 reports that on the rehearsal image the
    edge generated delivery-status notices on a client's request
    (`NOTIFY=SUCCESS`), despite the no-DSN invariant. **Not closed** until #147
    and the host change land and the live edge is verified.
- **The B2 recipient allow-list (`infinity-core` !166).** Ops' records name it
  as merged and reconciled before the listener went public. This repository has
  not reviewed what it does, or how it relates to the empty
  `relay_recipient_maps` and the uniform `250` this contract requires; Ops'
  acceptance A3 reports an unknown recipient "relayed and discarded, no DSN".
- **ADR-004's gates and acceptance row V, as Ops maps them (for the record
  only).** Ops matched ADR-004's
  [remaining gates](../adr/0004-initial-inbound-provider-strategy.md#gates-that-remain-closed)
  and row V of [`production-ops-acceptance.md`](../dev/production-ops-acceptance.md)
  to its launch records by content, reading the records only (#124, comment
  6686721348). Paths are as Ops gives them, under `/opt/infinity/workspace/`;
  sha256 prefixes in parentheses. Full detail:
  `ti-delivery-002/stage-b/fasttrack/edge-docs/ADR004-GATE-RECORDS.md`.

  | gate | Ops' finding | Ops record |
  |---|---|---|
  | **1** named owner and runbooks | **Partly**, for the owner-only window. The `operational-owner.md` sign-off is **unsigned**. There is **no runbook for patching, provider incidents or CVE response** | `ti-008-beta/launch/OWNER-FORM-1A-3A-4A.md` (`371074e3b9645def`) names the owner; `ti-008-beta/launch-20261028/LAUNCH-RUNBOOK.md` (`25dd94da4c1f8188`) |
  | **4** apex contacts | Discharged, 2026-10-28 16:39Z. Cloudflare Email Routing forwarding to Microsoft 365, not a mailbox provider; the owner accepted that design on 2026-10-27 | `launch-20261028/fwd-checks-20261028.md` (`5c1efe792ea3a3b6`) |
  | **4a** `postmaster@inbox.testinbox.email` | Discharged at the RCPT stage, before and after MX: "tenant-domain replies byte-identical" | `launch-20261028/g3ext-rcpt.txt` (`c1e1e2fdc2ea472c`); repeated after MX in `a6-rcpt-after-mx.txt` |
  | **5** privacy, terms and legal | **NOT FOUND**: no Ops record discharges it; the records still list it as blocked | `ti-008-beta/launch/README.md` (`3d8343e412052623`), closeout P-2/P-4 |
  | **6** production readiness | **No single record**; only per-item records (the monitoring fire-test, secrets, the D3 storage cutover, the guards). **Backups cover the control plane only** | — |
  | **row V** edge queue-age alerting before any public MX | Alert **present and live-loaded before MX**: Grafana rule uid `edge-queue-ageing` (oldest message over 3600 s for 5 min, critical), installed 2026-09-16, live-loaded per a ruler read at 2026-10-28 16:34Z (MX at 18:12:03Z). It has **never been fired deliberately on the live system**, only in offline promtool and CI tests. There is **no alert on the deferred-mail count** | `SESSION-STATE.md.bak-20260919-134815` (`103036bef3952475`); the oldest rules backup (`a1b67659c2d39762`); `ruler.json` (`7b4e7abc72bbc921`) |

  Gates 1, 5 and 6 are recorded as Ops states them, partial, not found and no
  single record, and are **not** discharged. Whether any of them, or row V's
  missing live fire and deferred-count alert, needs action is the **owner's
  decision**. This record does not reopen the completed launch qualification.

---

*Dormant-era text follows, as written before 2026-10-28.*

**Status:** the contract is executable and blocking in CI (TI-005). The real edge
exists, is **dormant**, and is not connected to anything.

> **No public MX. No public SMTP. No production relay. Production is blocked
> independently** on OVH monitoring, backups and the `DOCKER-USER` origin lock —
> nothing in this document changes that, and nothing here deploys the real edge.

## What this is

ADR-004 (Accepted 2026-09-08) selects a dedicated self-hosted Postfix relay as
the initial production inbound architecture:

```
Internet ──:25──> Postfix edge (Contabo EU) ──private relay──> TestInbox ingestion
```

TI-005 makes that hop an **executable contract** rather than a description, so a
regression in it fails the same pipeline that protects the application — before
any public traffic is ever allowed.

The rehearsal runs the same configuration family as the real edge:

```
sender ──:25──> Postfix 3.8.6 ──relay:[ingestion]:2525──> SmtpGateway ──> Postgres/MinIO
```

## Why the dormant host's evidence is not sufficient

The real edge today renders `inbox.testinbox.email discard:` — it accepts mail
and drops it, because production ingestion does not exist yet. On that
configuration a tenant-looking recipient and a nonexistent one share a code path
*because both are discarded at the edge*, which tells you nothing about ADR-025.

The rehearsal therefore renders the **production relay form**. An unknown
recipient is genuinely relayed, and ingestion is genuinely the component that
resolves nothing and discards the body. That is the claim ADR-025 makes, and it
is only testable against a relaying edge. The edge entrypoint refuses to start a
`ci` profile without a relay target, and the contract gate rejects a `discard:`
tenant line in that profile, for exactly this reason.

## Ownership boundary

| Owned here (application repo) | Owned by Ops (`infinity-core`) |
|---|---|
| accepted tenant domain; RCPT/relay behaviour | host provisioning, Contabo firewall |
| the empty `relay_recipient_maps` invariant | nftables egress DROP (25/465/587) |
| Message-ID / header behaviour | systemd units |
| message-size ceiling | WireGuard keys and identities |
| queue and retry semantics | real host IPs and the relay target value |
| the no-DSN contract | monitoring agents |
| postmaster / system-recipient contract | reconcile and deployment plumbing |
| the automated rehearsal | runtime substitution values |

The real configuration is **rendered** from a host-only `.env.mail-edge`. This
repository owns the rendered *invariants*; the substitution values are Ops'. No
host secret, IP, WireGuard identity, nftables rule or systemd unit appears here,
and `deploy/mail-edge/contract.yaml` must never acquire one.

`deploy.sh` deliberately does **not** know about the edge. It is "the same script
a real host runs", and a real host does not deploy this — the production edge is
a separate machine Ops reconciles. The rehearsal starts the edge itself.

## The three-layer model

Mirrors the real host's `reconcile.sh`. Every layer is blocking.

| layer | what it proves | why it is not enough alone |
|---|---|---|
| **A. pre-start** | one coherent generation renders, maps compile, `postfix check` passes, rendered invariants hold | `postfix check` validates *syntax, not values* — it passes a configuration that kills smtpd at startup |
| **B. runtime health** | a real TCP session gets a `220` banner | a banner says nothing about whether the configuration is *correct* |
| **C. running config** | `postconf` shows the contract's values in the **effective** configuration | a correctly rendered file is not evidence of what Postfix loaded |

The known example is a zero-length `*_notice_recipient`: `postfix check` accepts
it, master starts, smtpd throttles with `bad string length 0 < 1`, and the MTA
accepts nothing while every file on disk looks right. The mutation suite proves
layer B catches it — and records that layer A did not.

**Coherent generations.** Ops hit a real defect where per-file rollback restored
`main.cf` and `master.cf` from different generations, and an older stock
`master.cf` reinstated a public `0.0.0.0:25` listener. `render.sh` stages all
files and moves them into place as a unit with a generation stamp, so no run can
mix two renders.

## The invariants, and why each exists

| invariant | why |
|---|---|
| `relay_recipient_maps` **empty** | a recipient map rejects nonexistent tenants at RCPT time and recreates the enumeration oracle ADR-025 removes |
| `local_header_rewrite_clients` **empty** | `always_add_missing_headers = no` is *not* sufficient; Postfix stamps Message-ID/Date/From for clients matching this, whose default covers locally submitted mail (ADR-019) |
| `default_transport = discard` | anything not in `transport_maps` is dropped, never sent — the structural half of no-DSN |
| `notify_classes` **empty** | no postmaster notifications of any class |
| `bounce_queue_lifetime = 0` | a bounce that cannot be delivered first time is discarded, not queued |
| `relayhost` **empty** | normal operation must never require Internet SMTP egress |
| `mynetworks` **loopback only** | `permit_mynetworks` is evaluated first; a container-derived value makes the test sender trusted and the open-relay proof vacuous |
| `message_size_limit` = 15 MiB | see below |
| `relay_destination_recipient_limit` ≥ recipients under test | at 1, Postfix splits one transaction into several downstream deliveries and the ADR-026 atomicity proof passes while testing nothing. It must be `relay_*`: Postfix derives per-transport overrides from the master.cf **service name**, and tenant mail is routed `relay:`, so `smtp_*` governs a transport this path never uses |
| the smtpd **listener** | `render.sh` defaults to the dormant loopback form so a forgotten flag cannot produce a world-listening MTA; the gate refuses a public listener in a production render |
| the **compiled** postmaster alias | a rendered aliases file that was never compiled looks identical on disk to one that was — layer C asserts `postalias -q`, not the file |

### The size contract

**The contract is a property, not a byte count:**

> every message the public edge accepts must remain acceptable to TestInbox
> ingestion after TestInbox-owned transport headers are added.

Both ceilings are **15,728,640 bytes (15 MiB)**. That they are equal is safe for
a structural reason, proven end to end in the edge suite: Postfix applies
`message_size_limit` to the message *including the trace header it is about to
add*, so its reserve grows as that header grows. The only residual is ingestion's
own header, bounded by the recipient and hostname rather than by anything a
sender controls. The suite includes a realistic long deployment hostname so the
proof does not rest on spare room production will not have.

Measured reserves are deliberately **not** encoded anywhere as invariants. They
are observations of Postfix 3.8.6's implementation, and a future version may
choose differently; the test walks down from the ceiling to find what is actually
accepted.

If that property ever fails, the answer is a clearly separated **internal
transport allowance** on the ingestion side — never a higher public ceiling.

## Postmaster

`postmaster@<tenant domain>` is routed `local:` by `transport_maps`, and the
specific-address entry precedes the domain entry so it wins. **The lookup happens
at delivery time, after acceptance**, which is what keeps it off the SMTP path:
its RCPT and DATA replies are identical to any other recipient's.

On the application side the local-part is already unavailable to tenants —
`LocalPartPolicy.DENYLIST` (ADR-021) contains it among the RFC 2142 role
addresses, and `validate()` lowercases before checking, so `postmaster`,
`Postmaster` and `POSTMASTER` are all refused. TI-005 added no new reservation
mechanism; it proves the existing one holds and does not leak into SMTP.

## CI differences from production

The `ci` profile may override **only** the keys listed under `ci_overridable` in
`contract.yaml`; `render.sh` refuses anything else, and the gate asserts the
production values separately in every profile. So a drift to Postfix's multi-day
queue defaults fails even while CI runs on short timers.

| parameter | production | CI | why |
|---|---|---|---|
| `maximal_queue_lifetime` | 4h | 300s | a 4h expiry test cannot run in CI. Long enough that a message outlives an ingestion restart, because the retry proof needs that; the expiry proof shortens it to 10s for its own message and restores it, since one value cannot satisfy both |
| `minimal_backoff_time` | 120s | 2s | first retry would idle two minutes |
| `maximal_backoff_time` | 600s | 4s | bounds the retry test |
| `queue_run_delay` | 120s | 2s | the queue runner would not fire inside a test |
| `smtpd_client_connection_count_limit` | 20 | 200 | anvil limits are per client IP and CI has one sender |
| `smtpd_client_connection_rate_limit` | 60 | 600 | as above |
| `smtpd_client_message_rate_limit` | 100 | 1000 | as above |
| `smtpd_tls_security_level` | may | none | the rehearsal has no certificate material and no TLS invariant to prove |
| `smtpd_tls_protocols`, `smtpd_tls_loglevel`, `tls_preempt_cipherlist` | set | absent | they go with the TLS block. Declared explicitly: dropping a parameter is a divergence, and this one was found by the profile-diff check rather than by enumeration |

Everything else — including `message_size_limit` — is identical, which is what
makes the boundary proof meaningful.

The allowlist is checked **structurally**, not merely declared: the mutation
suite renders both profiles and requires every differing key to appear in
`ci_overridable`. `render.sh` alone could only ever verify the list it wrote
itself, which cannot catch a parameter dropped without being declared.

**Not a config override:** the transport map carries an *address*, not a name.
Postfix's `smtp(8)` runs chrooted and cannot read `/etc/hosts` or reach a
container runtime's embedded DNS, so a service name would defer forever. The
entrypoint resolves it at start time and fails hard if it cannot. The real edge
relays to an IP across WireGuard, so this is the faithful form; `smtp_host_lookup`
stays at its production value.

## Provenance

The configuration family mirrors, and is compared against:

```
infinity-core   develop @ 52462f4fa6d9dd59c4cd4053f54a4beb02e501cd
reconcile.sh    blob   e383fddb95c699d65e17f58cea328c2603d9dd79
                sha256 cc308038ab0b1ba00387ee9c45f77e57fa94b46c68e2cdb93f1e56fee332b67d
live host       Postfix 3.8.6 on Ubuntu 24.04
```

The git blob id was verified directly against `infinity-core`. **The Ops handoff
recorded the sha256 one character short** (63 hex characters, missing a trailing
`d`); the 64-character value above is the verified one. The blob match confirms
it is the same file, so this was a transcription slip and not configuration
drift — recorded because a truncated hash can never match a computed one, which
defeats the drift detection it exists for.

## Ops activation prerequisites

**Discharged.** The one outstanding divergence between this contract and the real
dormant edge has been reconciled and live-verified by Ops. This repository does
not modify `infinity-core`.

| parameter | dormant edge, previously | application contract | live edge, now verified |
|---|---|---|---|
| `message_size_limit` | `26214400` (25 MiB) | `15728640` (15 MiB) | `15728640` (15 MiB) |

Ops ref `infinity-core 1ad8932c`, verified 2026-09-19.

That row is **historical reconciliation evidence, not live-host state**. This
document states the contract the edge must satisfy; it does not track what a host
this repository does not own is running at any later moment. Re-verification is
an Ops action, and a stale "verified" column is exactly the failure mode the
executable gates exist to catch — which is why the invariant itself stays pinned
at `15728640` under `required:` and is asserted on every render and every start.

TI-005 changed the authoritative Postfix **production contract** from the stale
25 MiB to 15 MiB, on the owner decision of 2026-09-18. The application's own
ingestion policy is unchanged, and the direction of the inequality is the part
that matters: **the edge ceiling must never exceed what ingestion accepts.** The
edge emits no DSN by construction, so an over-ceiling message would be answered
`250`, relayed, refused downstream, and silently discarded — the sender told it
was delivered, with no bounce ever contradicting that. Raising the edge above
ingestion reintroduces exactly that silent-loss window.

Discharging this prerequisite does **not** authorise public SMTP. It closes one
gate; ADR-004's remaining gates — a named operational owner above all, and the
production-readiness and legal prerequisites — stay closed, and production
remains NO-GO.

## What runs, and where

| gate | what it covers |
|---|---|
| `scripts/check-mail-edge-contract.sh` | the static half — a rendered generation against the contract |
| `scripts/check-mail-edge-contract.test.sh` | **the mutation proofs** — every gate must be shown able to fail |
| `deploy/synthetic/edge/contract.test.mjs` | the behavioural contract, through the edge, via the public SDK |
| `scripts/mail-edge-storage-proof.sh` | ADR-025's storage half: no row, no attachment, no object |
| `scripts/mail-edge-queue-proofs.sh` | 451 → queue → retry, and expiry without DSN |
| `scripts/mail-edge-atomicity-proof.sh` | ADR-026: one `DATA` with two recipients is ONE inbound event |
| `scripts/mail-edge-abuse-proofs.sh` | the abuse ceilings, driven to refusal at production values |

All are blocking in the rehearsal, which is itself a required check.

### Known gaps

Recorded rather than implied to be covered. None blocks the increment; all
matter before public SMTP.

> **2026-11-06:** public SMTP has existed since 2026-10-28 (see the
> [correction](#correction-2026-11-06-the-edge-is-internet-facing)). These gaps
> are open on the live edge; none is closed by that correction.

| gap | consequence |
|---|---|
| **`smtpd_client_message_rate_limit` is asserted statically, never exercised.** The recipient, connection-count and connection-rate ceilings are now driven to refusal at production values (`scripts/mail-edge-abuse-proofs.sh`); the message-rate ceiling is not — crossing it costs 101 accepted messages, which is load, not a boundary probe. | Its *value* is pinned and drift-checked; its *behaviour* is inferred from the other two anvil counters sharing the same enforcement path. |
| **`smtpd_recipient_restrictions` is not in the contract.** Only `smtpd_relay_restrictions` is pinned. Adding `reject_unverified_recipient` or `reject_unlisted_recipient` there is a plausible "anti-spam hardening" diff and a direct RCPT-time enumeration oracle. | Only the behavioural equivalence test would notice, and only in the rehearsal. |
| **No VRFY/EXPN probe.** `disable_vrfy_command = yes` is asserted statically with no behavioural check. | VRFY is the canonical recipient-existence oracle. |
| **Relay-bypass address shapes are untested.** The open-relay probe uses `user@foreign`; percent-hack, source-route and bang-path forms are not probed, and `allow_percent_hack`/`swap_bangpath` are unpinned. | Refused today by `reject_unauth_destination` operating on the resolved address — an inference about version-sensitive behaviour, which is the kind this document avoids relying on elsewhere. |
| **`postmaster@`'s Maildir is unbounded.** `mailbox_size_limit = 0`, no rotation, no expiry, fed by unauthenticated senders at up to 15 MiB. | An attacker who knows the domain accepts mail could fill the edge's disk. Ops owns the host, but no invariant states the expectation. |
| **The provenance record is not machine-checked.** The `infinity-core` ref, blob id and sha256 are constants no script compares against anything. | Drift is detectable by hand, not automatically. |
| **The generation stamp is written but never read.** `render.sh` emits it; nothing verifies the rendered files match it. | The coherent-generation property rests on the atomicity of the move, not on a check. |
| **The base image and package source are not reproducibly pinned.** `FROM ubuntu:24.04` is a moving tag, not a digest, and `apt-get install postfix` takes whatever the archive serves that day; no snapshot or repository pin exists. | A rebuild is not bit-reproducible, and the version the edge ends up running is decided by the archive rather than by this repository. The *runtime* version is not an open question — the entrypoint asserts `postconf -h mail_version` equals `provenance.postfix_version` and refuses to start on a mismatch, mutation-tested by `check-mail-edge-contract.test.sh`. So a drifted archive fails the build loudly instead of silently proving version-sensitive behaviours against a different MTA; what remains missing is the ability to *reproduce* a given build, not the ability to detect a wrong one. |

### Reading the logs

Two Postfix conventions mislead on first reading, and both appear in these
proofs:

- On expiry, `qmgr` logs **"returned to sender"** and the resulting bounce is
  logged by `postfix/discard` as **`status=sent`**. That means *dropped by the
  discard transport*, not transmitted. `relay=none` is the part that says nothing
  left. Asserting merely that the sender is never mentioned fails on correct
  behaviour.
- A deferred message's file mtime is its *next retry* time, not its arrival, so
  it never grows. Queue age must come from `postqueue -j` `arrival_time`.
- The two anvil ceilings are **indistinguishable in the SMTP reply**: exceeding
  either `smtpd_client_connection_count_limit` or `smtpd_client_connection_rate_limit`
  answers `421 4.7.0 <host> Error: too many connections from <ip>`. Only the
  maillog separates them — `Connection concurrency limit exceeded: N` versus
  `Connection rate limit exceeded: N`. A probe that asserts the reply alone
  cannot say which limit it measured, so the abuse proofs assert the warning too.
