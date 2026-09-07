# Release process

## Branches

| branch | means | automation |
|---|---|---|
| `feature/*`, `feat/*`, `fix/*` | work in progress | CI on every PR: contract, static analysis, backend, e2e, SDKs, web, plus an image build and an ephemeral rehearsal of the full deployment |
| `develop` | **staging candidate** | merge builds immutable artifacts and deploys them to staging, then runs the post-deployment synthetic suite |
| `master` | **production-approved candidate** | **none.** `master` does not auto-deploy anything, and no production pipeline exists |

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
build immutable images ─▶ push to GHCR ─▶ deploy digests to staging
   ─▶ run ONE migration job ─▶ wait for readiness
   ─▶ post-deployment synthetic test ─▶ record evidence
```

Any of those failing means **STAGING DEPLOYMENT FAILED**, including a synthetic
failure with every container running happily. There is no `continue-on-error`
on a deployment-critical stage.

Concurrency group `testinbox-staging` with cancellation **disabled**: two rapid
merges queue rather than racing, and a run is never cancelled mid-migration.

## develop → master

Open a release PR from `develop` to `master`. It is a human decision and a
human review; merging it marks the commit production-approved. It deploys
nothing today.

Before that PR, the following should be true and visible:

- the latest `develop` staging deployment is green, synthetic suite included;
- the digest set that passed staging is recorded (it is, in the workflow
  summary) — production promotion will deploy *those* digests, not a rebuild;
- no migration in the range makes artifact rollback unsafe, or the plan for it
  is stated.

## Deployment evidence

Every staging run writes a summary containing environment, commit SHA, all four
image digests, migration result, readiness result, synthetic result, deployment
timestamp and the URL. This is operational evidence: it is what a rollback
reads to find the previous known-good digest set.

## Still to be decided

- **Where staging is hosted** — [ADR-030](../adr/0030-staging-deployment-target.md),
  `VISION.md` §7. Until then the deployment job is inert and no provider has
  been chosen.
- **The production inbound-mail provider** — ADR-004, still `Proposed`. There is
  no public MX record for `testinbox.email`.
- **A production promotion pipeline** — deliberately out of scope here.
