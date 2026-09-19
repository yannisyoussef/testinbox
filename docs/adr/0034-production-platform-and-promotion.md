# ADR-034: Production Platform, Promotion Contract and Data Lifecycle

**Status:** Accepted (2026-09-19). Amends [ADR-028](0028-deployment-artifacts-and-promotion.md)
§1 (the promotion is now defined), [ADR-029](0029-schema-migration-execution.md)
§4 (readiness gains the database session bound), [ADR-030](0030-staging-deployment-target.md)
(its "Production" row and the "not authorised" consequence), and closes the
known gap named in [ADR-033](0033-idempotent-mutations.md) Consequences.
Does **not** amend [ADR-004](0004-initial-inbound-provider-strategy.md) or
[ADR-025](0025-unknown-recipient-handling.md): no MX, no public SMTP and no
edge-to-production relay follow from anything here.

## Context

TI-005 closed on both sides: the application-owned Postfix edge contract is
executable and blocking, and Ops reconciled the real, dormant edge to it.
Staging is live on the Infinity estate and receives immutable digest handoffs
from GitHub through GitLab. What did not exist was any of the following:

- a decided production application/data-plane target — `VISION.md` and
  `docs/dev/release-process.md` still said "undecided";
- a production configuration — the only deployed profile was named
  `staging`, and the obvious fix (copy it) would have created two documents
  that drift;
- a way to say *which* bytes are production-approved that survives the fact
  that images are built for the `develop` tip while the commit that lands on
  `master` may be a merge or a squash with a different SHA, in a repository
  that allows all three merge methods;
- any protection on `master` at all (#44), and nothing stable enough to
  require on it;
- a backup contract that did not quietly defeat the product's deletion
  promise;
- and three independently verified production blockers on the OVH host —
  no monitoring, no backups, no origin isolation — which no amount of
  application CI can close.

The owner decided the target. This ADR records that decision and everything
the application repository must establish so that a set of staging-proven
artifacts can be handed to a production estate that demonstrably satisfies
the contract — and no further.

## Decision

### 1. The production application/data plane is the existing OVH dedicated host in France

Operated through the Infinity Ops estate (`infinity/infinity-core`), exactly
as staging is. The public SMTP edge is **not** moved onto it: the dedicated
Contabo EU Postfix host remains a separate trust boundary, dormant, relaying
nothing, and TI-007 owns its activation. Two topologies, two hosts, one
private authenticated path between them that does not yet exist.

EU residency holds on both: edge in the EU, data plane in France; production
raw MIME never traverses the US staging host.

### 2. Ownership boundary

| The application repository owns | Ops (`infinity-core`) owns |
|---|---|
| the artifacts and their identity (digests, provenance) | host provisioning, firewall (`DOCKER-USER`/nftables), TLS at the edge |
| deployment invariants: one migration executor, expand-only migrations, readiness contents, stop grace, the LISTEN constraint | the reconcile implementation, migration execution, the deployment verdict |
| the fail-closed configuration a production process refuses to start without | the production secret material and its rotation |
| the data-lifecycle classification and the gate that checks a backup against it | the backup runtime, its retention, and the restore drill |
| the alerting contract: which signals mean what | the monitoring runtime and the alerts themselves |
| the synthetic suites and what they prove | running them from the paths that can reach the targets |

GitHub obtains **no** OVH SSH, Docker, database, object-storage or API
credential. The only cross-system secret it holds for production is a GitLab
pipeline trigger token that can start one pipeline, on a GitHub Environment
named `production-handoff` — named for what a green run means: *Ops accepted
the deployment request*. It never means production deployed.

### 3. Production promotes staging-proven digests and never rebuilds

Build once, promote many (ADR-028) becomes concrete:

```
develop tip S  ──build-images.yml──▶  4 digests D, each attested to S
      │                                       │
      ├─ handed to staging; staging reconciles and proves D
      │
      └─ release PR develop→master: Candidate identity, vulnerability policy,
         migration rollback safety  ──▶  ONE aggregate: Production promotion gate
                │
                ▼ human merge = production approval of S
      master ──human dispatch──▶ Production handoff: verify S again, hand D to Ops
                                                  │
                                       Ops: confirm D is what staging proved,
                                            reconcile, migrate, readiness,
                                            synthetics  ──▶ the verdict
```

**Identity.** A production candidate is a source SHA `S` together with the
four digests its `:S` tags resolve to, where each digest carries a provenance
attestation whose source-repository digest is `S`, whose signing workflow is
this repository's `build-images.yml`, whose source ref is `develop`, and which
was produced on a GitHub-hosted runner. Merge-commit parentage is deliberately
**not** the anchor: a squash or rebase merge leaves `S` out of `master`'s
history, and the repository allows both. The attestation is what a repointed
tag or a rebuilt image cannot fake.

**Approval.** On `master`, a candidate must additionally be the head of a
*merged* pull request into `master` from `develop`, read from the merged-PR
list so the merge method is irrelevant. A feature-branch commit with
perfectly good artifacts is refused.

**Handoff.** `scripts/gitlab-handoff.sh` accepts exactly two targets,
`staging` and `production`, and defaults to staging; anything else is refused
before a byte leaves the runner. The payload is the same six values as
staging's. **Staging evidence is not invented**: GitHub does not know whether
staging reconciled or its synthetics passed, and no field pretends it does.
Ops verifies, before deploying, that the digest set it is handed is the one
its own staging reconcile recorded as green (option A of the increment; a
cross-system credential for a green tick was rejected).

**Rollback** is the same handoff with a previously approved candidate: no
rebuild, verified identically. `deploy/rollback-floors.txt` lists commits a
candidate must contain; a candidate predating one is a rollback across a
behavioural break — the first is the V4 credential lifecycle, across which an
older artifact answers `401` to every managed key — and is refused unless the
hazard is acknowledged explicitly, and then still shouted. Database rollback
remains unautomated (ADR-029 §5).

### 4. Configuration fails closed, and production is decided in code

`staging` and `production` are Spring **profile groups** over one `deployed`
layer. Production adds a short overrides document; there is one description
of a deployed node and one list of differences, not two copies.

A profile document can be overridden by an environment variable, so the
document is the default and `DeploymentSafety` is the enforcement. A
production process refuses to start when: the production profile is absent
while `TESTINBOX_ENVIRONMENT` says production (staging configuration wearing
a production label), or the reverse; the mail domain is anything but
`inbox.testinbox.email` (ADR-004); the API has no HTTPS public origin; any
staging, rehearsal or loopback host appears in the base URL, database URL or
object-store endpoint; bucket creation is on; the ingress ceiling is
undeclared; limits are disabled; a bootstrap key is short, low-entropy or a
fixture. Every violation is reported at once. Staging is untouched by the
production rules, and its configuration is byte-for-byte what it was.

The constants — the environment name, the profile name, the tenant domain,
the non-production host fragments — live in `ProductionPolicy`, in code,
because the point of a production invariant is that no variable can move it.

### 5. Data lifecycle: what a backup may contain

Every table is classified in `deploy/backup/scope.txt`, and the migrations
are checked against it, so a new table forces a decision:

| kept — durable control plane | never backed up |
|---|---|
| `workspace`, `project`, `api_key` (verifiers only, ADR-032 §4), `exact_address_reservation`, `flyway_schema_history` | `inbox`, `message`, `attachment` (tenant content, ADR-009 TTL), `idempotency_record` (request fingerprints and inbox addresses, hours of retention), `rate_bucket`, `wait_lease` (rebuilt by the running system) |
| **object storage: nothing** — raw MIME and attachments are deliberately not backed up (owner decision recorded under ADR-004; ADR-009/025 reasoning) | |

Consequences the contract makes explicit: a restore recovers tenant and
credential identity and nothing a sender was promised would be deleted; a
restored database cannot point at raw objects that were never backed up,
because no message row is restored; the orphan sweep already reclaims the
reverse case (ADR-005). `scripts/check-backup-scope.sh` is the acceptance
gate for Ops's real dump, and it refuses a backup that examines nothing.

**HUMAN DECISION REQUIRED** — not decided here, and not decidable by
software: the backup retention period, and any RPO/RTO. Options and
consequences are in `docs/dev/production.md`.

### 6. The three OVH blockers gate readiness, and only Ops evidence closes them

Monitoring, backups and origin isolation are each an *acceptance contract*
with a required invariant, a proof, an owner and a blocking result
(`docs/dev/production-ops-acceptance.md`). The application contributes
executable pieces — the alerting contract over existing metrics, the backup
gate, the origin-isolation and build-identity synthetics — but TI-006 is not
closed by the application PR. It reports **APPLICATION SIDE COMPLETE /
WAITING ON OPS** until Ops supplies direct evidence for all three.

### 7. `master` protection: one stable, falsifiable context

`master` requires a pull request, no direct push, no force-push, no deletion,
and the single context `Production promotion gate`. That aggregate `always()`
reports, depends on every promotion leg, and fails on a failed, cancelled,
skipped, missing or *unlisted* leg — with a self-test proving each refusal.
Its name does not move when a leg does, which is the whole difference from
`develop`'s thirteen matrix-expanded contexts (#46, deliberately untouched).
Protection is applied only after the gate has been proven red and green on a
real pull request; if the permission to apply it is unavailable, that remains
a named human action and #44 stays open.

### 8. What this ADR does not authorise

A production **dark** deployment — exact staging-proven digests, Ops-supplied
secrets, one migration, readiness, synthetics from an authorised path, SMTP
ingestion private only. Not: an MX record, public TCP/25, the edge relaying
to production, tenant traffic, or a production GO. Those remain with TI-007
and the human gates it does not discharge either: a named, accountable
operational owner for the public SMTP edge, and the legal/compliance
activation (privacy policy, terms, abuse process, retention documentation,
review).

## Alternatives considered

- **Move the SMTP edge onto OVH too.** Rejected: the edge terminates
  untrusted Internet SMTP and the application host holds tenant data; one
  host would collapse the two trust boundaries ADR-004 kept apart.
- **Anchor candidate identity on the master merge commit's second parent.**
  Rejected: only true for merge commits, and the repository allows squash
  and rebase; a gate that is right for one merge method is a gate that
  breaks silently when a human picks another.
- **Rebuild on master so master has its own images.** Rejected outright:
  ADR-028 §1. Two builds of one commit are two artifacts.
- **Let GitHub query Ops for the staging verdict.** Rejected for this
  increment: it needs a cross-system read credential to produce a green tick
  that Ops can already assert internally, and the trust boundary is worth
  more than the convenience.
- **Copy `application-staging.yaml` to `application-production.yaml`.**
  Rejected: two documents that drift by edit distance, exactly the failure
  the profile group removes.
- **Refuse to boot on an unbounded database session timeout.** Rejected in
  favour of readiness, as ADR-029 already reasoned: a not-ready node is
  diagnosable, a crash loop is not. And enforced in production only, so a
  staging estate that has not set it stays in service and visible.
- **Back up everything, restore everything.** Rejected: it would retain
  message content past the TTL and past deletion in a copy nobody promised,
  and restore rows pointing at objects deliberately not backed up.
- **Require develop's thirteen contexts on master as well.** Rejected: the
  develop tip already satisfied them to reach develop; master needs the
  promotion legs, and a matrix-coupled name deadlocks the branch the day a
  runtime line changes (#46).

## Consequences

- `docs/dev/production.md` is the production contract; `docs/dev/production-ops-acceptance.md`
  is the Ops acceptance table; `deployment.md`, `release-process.md`,
  `rollback.md`, `staging.md`, `VISION.md` and `CLAUDE.md` are corrected
  where they said production was undecided, `develop` unprotected, or
  ADR-004 still open.
- Two new workflows/legs run **only on a promotion pull request or a manual
  dispatch on master**; the ordinary pull request pays for three static
  self-tests and nothing else.
- `master`'s first release merge is a human decision that this ADR makes
  machine-preconditioned but does not make. The four master-only commits
  were audited: all superseded on `develop`; nothing is lost by the merge.
- Readiness on the API grows by `dbSession`. In production a database that
  disables `idle_in_transaction_session_timeout` keeps the node out of
  service until Ops sets it; that is the ADR-033 gap, closed.
- A future self-service signup, a managed database, or a second
  application node each re-open specific parts of this: the backup scope
  (content volume), the LISTEN constraint (pooled endpoints), and the
  readiness cascade ADR-030 already flags. None is silent; each is named in
  `docs/dev/production.md`.
