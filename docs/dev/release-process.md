# Release process

## Branches

| branch | means | automation |
|---|---|---|
| `feature/*`, `feat/*`, `fix/*` | work in progress | CI on every PR: contract, static analysis, backend, e2e, SDKs, web, plus an image build and an ephemeral rehearsal of the full deployment |
| `develop` | **staging candidate** | merge builds immutable artifacts, rehearses the deployment, and **hands the digest set to GitLab Ops**, which deploys |
| `master` | **production-approved candidate** | a release PR runs the promotion legs — candidate identity, vulnerability policy, migration rollback safety — behind one required context, `Production promotion gate`. Merging is the approval; it deploys nothing. `Production handoff` is a separate, manual dispatch on `master` |

Nothing in this repository deploys production. `Production handoff` hands a
verified candidate to Ops, which deploys and owns the verdict
([production.md](production.md), ADR-034).

## Feature → develop

1. Branch from `develop`.
2. Open a PR **targeting `develop`**. Required checks:
   - OpenAPI lint + backwards-compatibility gate (ADR-015/022);
   - Detekt, gitleaks, deployment-gate self-tests;
   - backend build with the test-execution verifier;
   - e2e acceptance, both SDK matrices, web Playwright;
   - image build and the ephemeral staging rehearsal, including the full
     synthetic workflow through a real TLS ingress.
3. If the PR contains a migration, say in the description whether artifact
   rollback across it stays safe — see [rollback.md](rollback.md).
4. Merge. The `Staging` workflow then does:

```
GitHub:  build immutable images ─▶ push to GHCR ─▶ ephemeral rehearsal
            ─▶ RELEASE HANDOFF to GitLab infinity-core
Ops:        host reconcile ─▶ migration job ─▶ readiness
            ─▶ post-deployment synthetic suite
```

**The line between them matters.** A green `Staging` workflow means the
candidate was handed over and accepted. Everything below the line happens
asynchronously in `infinity-core`, and **that is where the deployment verdict
lives** — GitHub cannot and does not report it. The job is named *Release
handoff to GitLab Ops* and its environment is `staging-handoff` precisely so
nobody reads it as a deployment.

A handoff failure — a malformed digest, a missing token, a non-2xx from the
trigger API — fails the job, and nothing is handed over. There is no
`continue-on-error` on that path.

Concurrency group `testinbox-staging-<ref>` with cancellation **disabled**: two
rapid merges queue rather than racing, and PR rehearsals do not contend with
real handoffs.

## develop → master

Open a release PR from `develop` to `master`. It is a human decision and a
human review; merging it marks the commit production-approved. It deploys
nothing today.

The `Release candidate` workflow runs three promotion legs and one aggregate:

| leg | policy |
|---|---|
| Candidate identity | **Blocks** unless the PR head is `develop`, all four `:sha` images exist, and each digest carries a provenance attestation for that commit from `build-images.yml`. Uploads `release-manifest.json`. `scripts/verify-production-candidate.test.sh` proves its refusals. |
| Container vulnerabilities | **Blocks** on HIGH/CRITICAL **with a fix available** (`--ignore-unfixed`). Informational on develop — see ADR-028's amendment for why the two differ. |
| Migration rollback safety | **Blocks** if any migration in the release range is rollback-breaking, including one that *declared* itself so. |
| **`Production promotion gate`** | The one context `master` requires. `always()` reports; fails on a failed, cancelled, skipped, missing or unlisted leg (`scripts/promotion-gate.test.sh`). |

After the merge, production is reached only by dispatching `Production
handoff` on `master` with the approved candidate SHA — see
[production.md](production.md#promotion-and-handoff).

A **declared** rollback-unsafe migration (`-- testinbox:rollback-unsafe:` in the
file) is allowed on develop — it is deliberate and visible — but it stops a
release, because artifact rollback across it does not work. Handling it means
either splitting it expand/contract across two releases, or accepting in
writing that this release cannot be rolled back by redeploying the previous
digests. That is a decision, not a formality; see [rollback.md](rollback.md).

Also true and visible before the PR:

- the latest `develop` handoff was accepted **and Ops reports the deployment
  green**, synthetic suite included — check `infinity-core`, not GitHub;
- the digest set that passed staging is recorded (in the handoff summary) —
  production promotion will deploy *those* digests, not a rebuild.

## Evidence, and where to find it

| question | where |
|---|---|
| What did GitHub build and hand over? | The `Staging` run summary: commit, four digests, GitLab pipeline URL |
| Did the artifact pass the application's own deployment tests? | The same run — the ephemeral rehearsal and its 20-assertion synthetic suite |
| Did staging actually deploy? Did the migration run? Is it healthy? | **`infinity/infinity-core`.** Not GitHub |
| What is running right now? | `testinbox_build{service,git_sha,version}` in Prometheus |
| What was the previous known-good digest set? | The previous `Staging` run summary |

## Required GitHub configuration — HUMAN ACTION

Two things must be created by hand before the first merge to `develop` after
this change, or the handoff job fails:

1. **A GitHub Environment named `staging-handoff`** — deliberately not
   `staging`, so nothing renders the handoff as a completed deployment.
   Consider adding required reviewers: it is the last human gate before Ops
   deploys.
2. **Secret `GITLAB_TRIGGER_TOKEN`** on that environment — a GitLab pipeline
   trigger token for `infinity/infinity-core`. It can start one pipeline and do
   nothing else.

Optional repository **variables**, both with working defaults:
`GITLAB_OPS_PROJECT` (default `infinity%2Finfinity-core` — URL-encoded; an
unencoded path is rejected) and `GITLAB_OPS_REF` (default `develop`).

## Branch protection

`develop` **is protected** (verified via the GitHub API on 2026-09-19):
pull request required, thirteen required status checks with strict
up-to-date, force pushes and deletion disabled, `enforce_admins` off. The
matrix-expanded context names are a known coupling (#46, deliberately left
for its own increment).

Settings on `develop`:

- require a pull request before merging;
- prohibit direct pushes;
- require these checks to pass (names as GitHub actually reports them):
  - `Backend (format, compile, unit + integration + architecture)`
  - `Acceptance (black-box, Karate, JVM + TS SDK live)`
  - `Static analysis (Detekt, deployment gates, secret scan)`
  - `OpenAPI contract (lint + backwards compatibility)`
  - `Web UI (build + Playwright sandbox proofs)`
  - `JVM SDK (Java 17 baseline, consumer matrix) (17)` / `(21)` / `(25)`
  - `TypeScript SDK (consumer matrix) (22)` / `(24)` / `(26)`
  - `Ephemeral staging rehearsal + synthetic suite`
  - `Immutable artifacts (build only) / Build (no push) OCI images`
- disable force pushes;
- disable branch deletion.

`Dependency vulnerabilities (informational)` is deliberately **not** in the
list: it is non-blocking by design (ADR-028, `docs/quality/strategy.md`).

### master

`master` had **no protection** when TI-006 started (#44). It is the
production-approved branch, so it gets the *promotion* gate, not a copy of
`develop`'s list — a matrix-coupled name deadlocks a branch the day a runtime
line changes (#46), and the develop tip already satisfied those thirteen to
reach `develop`.

Required on `master` (ADR-034 §7):

- require a pull request before merging; no direct pushes;
- required status check: **`Production promotion gate`** (strict);
- force pushes disabled; deletion disabled;
- `enforce_admins` off, as on `develop`.

Sequencing is deliberate: the gate is first **proven** on a real release
pull request — reported for a docs-only PR, green on a develop candidate,
red on a feature-branch candidate — and only then required. If
repository-admin access is unavailable, that is a named human action and #44
stays open; nothing here claims it done.

A required status check guards against mistakes, not adversaries: a
`pull_request` workflow runs the pull request's **own** copy of
`release-candidate.yml`, so a pull request can rewrite the gate it is judged
by. The hardened form is a repository **ruleset** that requires the workflow
from `master`'s copy ("Require workflows to pass", pinned to
`refs/heads/master`). That is possible only once `master` holds the file —
i.e. after the first release merge — and is the second step of #44, after the
status check. The `production-handoff` environment's `master`-only branch
policy and required reviewer already exist and are verifiable by API.

Also add `Synthetic unit tests (edge invariant classifier)` — it runs inside the
`Static analysis` job, so requiring that job covers it.

## Decided since this document was first written

- **The production inbound-mail provider** — ADR-004 is **Accepted**: a
  dedicated, dormant Postfix edge, reconciled to the application-owned
  contract (TI-005). There is still no public MX; activation is TI-007.
- **Production hosting and its promotion path** — ADR-034: the OVH dedicated
  host in France, reached through `Production handoff`. Not live.
- **Propagating the Ops deployment verdict back to GitHub** — deliberately
  **not** done. Ops correlates the handed-off digest set with its own
  staging record before deploying (ADR-034 §3); a cross-system credential
  for a green tick was judged not worth the trust boundary it crosses.
