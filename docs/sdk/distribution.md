# Package Distribution Strategy

See [ADR-017](../adr/0017-maven-npm-distribution.md) (status: Proposed — see
Human Decisions Required in `VISION.md` regarding namespace/account
ownership).

## Maven Central

- Group: `email.testinbox`. Initial artifact: `testinbox-client`.
- Likely follow-ons: `testinbox-kotlin` (coroutine-idiomatic extensions, if
  they end up not fitting in the base client), `testinbox-junit5`,
  `testinbox-testng`.
- **Signing**: GPG-signed artifacts as required by Central; key custody is a
  Human Decision (who holds the private key, how it's rotated).
- **Publish mechanism**: Gradle `maven-publish` + a signing plugin, driven
  from CI on tagged releases — never a manual `./gradlew publish` from a
  laptop once CI exists.
- **Versioning**: semantic versioning; `0.x` during pre-MVP-validation churn,
  `1.0.0` once the REST v1 contract and SDK surface are considered stable
  enough to commit to compatibility guarantees.
- **Snapshots**: `-SNAPSHOT` versions published from `main` CI builds to
  Sonatype snapshots repo for internal/early-adopter testing, never
  advertised as consumable outside the team until promoted.

## npm

- Scope: `@testinbox`. Initial package: `@testinbox/client`.
- Likely follow-ons: `@testinbox/playwright`, `@testinbox/cypress`.
- **Provenance**: publish with npm's provenance attestation
  (`npm publish --provenance`, requires CI running on a supported provider
  with OIDC) once CI exists, to give consumers supply-chain assurance.
- **2FA / account custody**: who controls the `@testinbox` npm org and its
  publish tokens is a Human Decision, not resolved here.
- **Versioning**: semver, mirroring the Maven package's major-version
  cadence where the underlying REST contract is the shared driver, but not
  forced to identical version numbers across ecosystems — each ecosystem's
  changelog is generated from its own commit history (Changesets for npm,
  conventional-commit-driven release notes for Maven).

## Cross-cutting

- **Runtime baselines** (see [ADR-023](../adr/0023-sdk-runtime-baselines.md)):
  JVM artifact targets Java 17 bytecode (tested on 17/21/25) regardless of
  the backend's Java 25 runtime; npm package declares `engines: ">=20"`
  (tested on 20/22/24) regardless of the Node LTS used to build/publish.
- **Dependency policy**: minimize runtime dependencies in both SDKs (a test
  library that drags in a large dependency tree is a real adoption cost);
  prefer the platform's built-in HTTP client (`java.net.http` on JVM,
  global `fetch` on Node ≥20/browsers) over adding an HTTP library
  dependency, revisited only if a concrete gap is found.
- **Reproducible builds / supply chain**: dependency lockfiles committed
  (`package-lock.json`/`gradle.lockfile` or verification metadata),
  dependency and vulnerability scanning in CI (see
  [`docs/quality/strategy.md`](../quality/strategy.md)).
- **Compatibility policy**: an SDK major version bump is required for any
  breaking change to its own public API, independent of REST API versioning
  (a REST v2 does not necessarily force an SDK major bump if the SDK can
  keep targeting v1 semantics or abstract the difference) — see
  [ADR-016](../adr/0016-sdk-compatibility-versioning.md) (Proposed).
- **Nothing is published during the inception stage.** Publishing setup
  (Sonatype namespace verification, npm org creation) requires the Human
  Decisions listed in `VISION.md` first.
