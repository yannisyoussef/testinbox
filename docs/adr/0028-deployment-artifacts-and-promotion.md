# ADR-028: Deployment Artifacts and Promotion by Digest

**Status:** Accepted (§6 amended 2026-09-07 by TI-DEPLOY-002: the container
vulnerability policy is now environment-sensitive — see "Amendment: vulnerability
policy by environment" at the end)

## Context

`develop` is intended to be a real integration/staging branch, not just a
long-lived Git branch: a merge to `develop` should produce something
deployable, deploy it, and prove it works. `master` remains the
production-approved branch.

The failure mode this decision exists to prevent is the one where staging and
production are *rebuilt* independently from the same source. Two builds of the
same commit are not the same artifact — base images move, transitive
dependency resolution drifts, a build-time network fetch returns something
different. "It passed in staging" then says nothing about the bytes that
reach production.

The repository has three long-running deployable processes today: the REST API
(`backend/api`) and the inbound SMTP gateway (`backend/ingestion`), which
ADR-001 keeps as separate deployables, plus the Next.js inspection UI (`web`),
which ADR-013 places in the same monorepo. ADR-029 adds a fourth, one-shot one:
the migration executor. ADR-023 already distinguishes the backend's *private*
runtime choice (Java 25) from the SDKs' *public* distribution contract.

## Decision

1. **Build once, promote many.** A commit is built into OCI container images
   exactly once, in CI. Staging deploys those images. A future production
   promotion deploys the *same* images. No environment rebuilds from source.

2. **The digest is the deployment identity.** `sha256:…` is what a deployment
   records, what a rollback names, and what deployment evidence reports. Tags
   (`:<git-sha>`, `:develop`) are convenience references that may be moved or
   garbage-collected; `latest` is never an authoritative deployment reference
   and is not published.

3. **Registry: GitHub Container Registry** (`ghcr.io/<owner>/testinbox-api`,
   `-ingestion`, `-web`), authenticated with the workflow's own short-lived
   `GITHUB_TOKEN` identity. No long-lived registry password exists.

4. **Images carry no secrets and no configuration.** Everything
   environment-specific arrives as environment variables at run time. An image
   is therefore promotable across environments unchanged, which is what makes
   (1) meaningful.

5. **Runtime images contain no build tooling and run as a non-root user.**
   Multi-stage builds; the runtime stage has a JRE (or the Node standalone
   output) and the application, nothing else.

6. **Provenance and SBOM** are attached as build attestations, and container
   vulnerability scanning is **informational**, consistent with the existing
   posture for OSV-Scanner in `docs/quality/strategy.md`. A CVE published in a
   base image is not a regression introduced by the merge that happens to run
   next; making every historical CVE a deployment blocker is the noisy-gate
   failure mode that gets the tooling switched off.

## Alternatives considered

- **Deploy from a Git checkout / rebuild per environment.** Rejected: it is
  precisely the drift this ADR exists to prevent, and it makes rollback a
  source-control operation with a fresh, unvalidated build.
- **Promote by mutable tag (`:develop`, `:latest`).** Rejected: a tag can be
  repointed after validation, so "the artifact we tested" is not
  reconstructible. Tags remain, but only as human-readable aliases.
- **A single image containing API + ingestion.** Rejected: ADR-001 keeps the
  gateway that terminates untrusted SMTP a separate deployable from the
  authenticated API, with independent scaling and blast radius.
- **Publishing to Docker Hub or a cloud-provider registry.** Rejected for now:
  GHCR needs no additional account, no long-lived credential, and no provider
  decision — and provider selection is explicitly still open (ADR-030).

## Consequences

- Rollback is "deploy the previous digest set", which is fast and exact
  (`docs/dev/rollback.md`). It does **not** roll back the database — see
  ADR-029.
- Deployment evidence must record digests, not tags, or the guarantee is
  unverifiable. The staging workflow writes them into the job summary.
- The registry becomes part of the deployment critical path; a GHCR outage
  blocks deployment. Accepted: the alternative is a rebuild, which is worse.
- Because images are configuration-free, a misconfigured environment fails at
  *startup*, not at build. ADR-029 and the staging configuration validation
  make that failure loud and early rather than a silently insecure default.

## Amendment: vulnerability policy by environment (2026-09-07)

The original §6 made container scanning informational everywhere, reasoning by
analogy with OSV-Scanner: a CVE published in a base image is not a regression
introduced by the merge that happens to build next, and blocking unrelated work
on it is the noisy-gate failure mode that ends with the tooling switched off.

That reasoning is right for **develop**, where the cost of a false block is
paid several times a day by people whose change has nothing to do with the
finding. It is weak for **promotion to production**, which is a deliberate,
infrequent, human-initiated act — exactly the moment when "we know about this
and shipped anyway" should require a decision rather than a shrug.

So the policy splits:

| Path | Policy |
|---|---|
| pull request, `develop` | **Informational.** Findings are printed; nothing is blocked. |
| promotion to `master` | **Blocking** on `HIGH`/`CRITICAL` — *and only where a fix exists*. |

`--ignore-unfixed` applies in both modes, and that is the load-bearing half.
Refusing to promote over a vulnerability nobody can remediate does not make the
release safer; it makes the release not happen, which is usually the worse
outcome and is how a blocking scanner earns a permanent bypass. A finding with
a published fix is a different proposition: it is actionable, so requiring it
to be acted on before production is a real gate rather than a toll.

Enforcement lives in `.github/workflows/build-images.yml` behind the
`enforce_vulnerability_policy` input, which only the promotion workflow
(`release-candidate.yml`) sets. Nothing else about ADR-028 changes: images are
still built once, promoted by digest, and the promotion path never rebuilds or
re-pushes — it scans the artifacts that already exist.

This amendment weakens no other gate. gitleaks, provenance, SBOM, the non-root
assertion, digest ownership validation and the migration rollback-safety gate
are unchanged.
