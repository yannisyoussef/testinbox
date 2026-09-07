# Release process

## Branches

| branch | means | automation |
|---|---|---|
| `feature/*`, `feat/*`, `fix/*` | work in progress | CI on every PR: contract, static analysis, backend, e2e, SDKs, web, plus an image build and an ephemeral rehearsal of the full deployment |
| `develop` | **staging candidate** | merge builds immutable artifacts, rehearses the deployment, and **hands the digest set to GitLab Ops**, which deploys |
| `master` | **production-approved candidate** | the promotion gates (vulnerability policy, migration rollback safety) run. **No deployment.** No production pipeline exists |

Nothing in this repository deploys production. That is a separate increment
and a separate decision.

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

The `Release candidate` workflow runs the two promotion gates automatically:

| gate | policy |
|---|---|
| Container vulnerabilities | **Blocks** on HIGH/CRITICAL **with a fix available** (`--ignore-unfixed`). Informational on develop — see ADR-028's amendment for why the two differ. |
| Migration rollback safety | **Blocks** if any migration in the tree is rollback-breaking, including one that *declared* itself so. |

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

## Required branch protection — HUMAN ACTION

`develop` is currently **unprotected** (verified via the GitHub API on
2026-09-07: `GET /repos/.../branches/develop` returns `"protected": false`).
Nothing in this repository can configure that; it needs repository-admin
access. Until it is set, a direct push to `develop` hands artifacts to Ops with
no review and no CI.

Required settings on `develop`:

- require a pull request before merging;
- prohibit direct pushes;
- require these checks to pass (names as GitHub actually reports them):
  - `Backend (format, compile, unit + integration + architecture)`
  - `Acceptance (black-box, Karate, JVM + TS SDK live)`
  - `Static analysis (Detekt, deployment gates, secret scan)`
  - `OpenAPI contract (lint + backwards compatibility)`
  - `Web UI (build + Playwright sandbox proofs)`
  - `JVM SDK (Java 17 baseline, consumer matrix) (17)` / `(21)` / `(25)`
  - `TypeScript SDK (consumer matrix) (20)` / `(22)` / `(24)`
  - `Ephemeral staging rehearsal + synthetic suite`
  - `Immutable artifacts (build only) / Build (no push) OCI images`
- disable force pushes;
- disable branch deletion.

`Dependency vulnerabilities (informational)` is deliberately **not** in the
list: it is non-blocking by design (ADR-028, `docs/quality/strategy.md`).

The same protections should be applied to `master`, additionally requiring
`Promotion vulnerability policy` and `Migration rollback safety`.

## Still to be decided

- **The production inbound-mail provider** — ADR-004, still `Proposed`. There is
  no public MX record for `testinbox.email`. Choosing a staging host did not
  choose this.
- **Production hosting and its promotion pipeline** — OVH is the stated
  intention (ADR-030) and nothing is deployed there. `master` runs gates, not
  a deployment.
- **Propagating the Ops deployment verdict back to GitHub.** Today the
  authoritative staging result lives only in `infinity-core`. Closing that gap
  should not mean minting broad cross-system credentials for the sake of a
  green tick; it needs a deliberate design.
