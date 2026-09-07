# ADR-028: Deployment Artifacts and Promotion by Digest

**Status:** Accepted

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

The repository has three deployable processes today: the REST API
(`backend/api`), the inbound SMTP gateway (`backend/ingestion`) — separate
deployables since ADR-001 — and the Next.js inspection UI (`web`). ADR-013
keeps them in one monorepo, and ADR-023 already distinguishes the backend's
*private* runtime choice (Java 25) from the SDKs' *public* distribution
contract.

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
