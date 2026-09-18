# The Postfix mail edge — application-owned contract

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
is only testable against a relaying edge. `render.sh` refuses the dormant form in
the `ci` profile for exactly this reason.

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
| `smtp_destination_recipient_limit` ≥ recipients under test | at 1, Postfix splits one transaction into several and the ADR-026 atomicity proof passes while testing nothing |

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
| `maximal_queue_lifetime` | 4h | 20s | a 4h expiry test cannot run in CI |
| `minimal_backoff_time` | 120s | 2s | first retry would idle two minutes |
| `maximal_backoff_time` | 600s | 4s | bounds the retry test |
| `queue_run_delay` | 120s | 2s | the queue runner would not fire inside a test |
| `smtpd_client_connection_count_limit` | 20 | 200 | anvil limits are per client IP and CI has one sender |
| `smtpd_client_connection_rate_limit` | 60 | 600 | as above |
| `smtpd_client_message_rate_limit` | 100 | 1000 | as above |
| `smtpd_tls_security_level` | may | none | the rehearsal has no certificate material and no TLS invariant to prove |

Everything else — including `message_size_limit` — is identical, which is what
makes the boundary proof meaningful.

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

Drift between this contract and the real dormant edge, to be reconciled **by Ops,
before public SMTP or MX is ever enabled**. This repository does not modify
`infinity-core`.

| parameter | this contract | dormant edge |
|---|---|---|
| `message_size_limit` | `15728640` (15 MiB) | `26214400` (25 MiB) |

TI-005 changes the authoritative Postfix **production contract** from the stale
25 MiB to 15 MiB, on the owner decision of 2026-09-18. The application's own
ingestion policy is unchanged. The edge ceiling must never exceed what ingestion
accepts: the edge emits no DSN, so an over-ceiling message would be answered
`250` and then silently discarded downstream.

## What runs, and where

| gate | what it covers |
|---|---|
| `scripts/check-mail-edge-contract.sh` | the static half — a rendered generation against the contract |
| `scripts/check-mail-edge-contract.test.sh` | **the mutation proofs** — every gate must be shown able to fail |
| `deploy/synthetic/edge/contract.test.mjs` | the behavioural contract, through the edge, via the public SDK |
| `scripts/mail-edge-storage-proof.sh` | ADR-025's storage half: no row, no attachment, no object |
| `scripts/mail-edge-queue-proofs.sh` | 451 → queue → retry, and expiry without DSN |

All are blocking in the rehearsal, which is itself a required check.

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
