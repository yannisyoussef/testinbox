# ADR-023: Public SDK Runtime Baselines

**Status:** Accepted (corrects `docs/sdk/architecture.md` and
`docs/sdk/distribution.md`)

## Context

The SDK architecture document described the JVM SDK as "Kotlin, Java 25
target". Java 25 is the backend's runtime — a private deployment choice.
A **public testing library on Maven Central** is consumed inside other
teams' test suites, which overwhelmingly run on LTS releases (Java 17 and
21 remain dominant in 2026). Requiring Java 25 bytecode would exclude
most of the addressable market for no benefit: nothing in the SDK (HTTP
long-polling, JSON, matchers) needs post-17 APIs. The backend's runtime
and the SDK's bytecode baseline are independent decisions.

The npm side had the mirror-image gap: `docs/sdk/distribution.md`
referenced "Node ≥18" (EOL since April 2025) and did not distinguish the
Node used to *build/publish* the package from the minimum Node a
*consumer* may run.

## Decision

### JVM SDK (`email.testinbox:testinbox-client`)

- **Backend runtime: Java 25** — unchanged, irrelevant to consumers.
- **SDK implementation: Kotlin**, compiled with **bytecode/API baseline
  Java 17** (`jvmTarget=17` plus `-release`-style API checking so no
  post-17 JDK API is referenced; `java.net.http` needs only Java 11).
  Build toolchain may be a newer JDK; the *artifact* is Java 17.
- **CI test matrix**: consumer-perspective tests on Java 17, 21, and 25.
- Kotlin metadata / `kotlinx-coroutines` versions are chosen
  conservatively (modest `apiVersion`/`languageVersion`, oldest
  maintained coroutines line) so consumers on older Kotlin toolchains can
  depend on the SDK without forced upgrades.
- The baseline is raised only deliberately (at minimum a minor version
  with release-note prominence; treat as breaking for consumers pinned to
  a dropped LTS).

### npm SDK (`@testinbox/client`)

- **Build/publish toolchain**: current active Node LTS in CI — a private
  choice, invisible to consumers.
- **Minimum supported consumer runtime**: `"engines": { "node": ">=20" }`
  (global `fetch` available; nothing newer required). Node 18 is EOL and
  is not a supported floor.
- **CI test matrix**: Node 20, 22, and 24 (kept aligned with maintained
  release lines over time).
- Dual ESM/CJS build unchanged (ADR-014 / `docs/sdk/architecture.md`).
- Raising the floor follows the same deliberate-bump policy as the JVM
  baseline.

## Alternatives considered

- **SDK bytecode = backend runtime (Java 25)**: rejected — conflates a
  deployment choice with a distribution contract; excludes Java 17/21
  consumers.
- **Java 8 or 11 baseline for maximum reach**: rejected — pre-17 share in
  actively maintained *test suites* is small and shrinking, and 17 keeps
  records/sealed types and modern TLS defaults available internally.
- **Tracking only the newest Node LTS as the floor**: rejected — a
  testing library used inside CI images should be generous; the floor is
  the oldest line with the APIs we need, not the newest line available.

## Consequences

- `docs/sdk/architecture.md` and `docs/sdk/distribution.md` updated.
- CI must include the consumer-matrix jobs from the first SDK build; the
  Java 17 API check must be enforced mechanically, not by convention.
- The backend is free to adopt future Java versions without touching the
  SDK contract.
