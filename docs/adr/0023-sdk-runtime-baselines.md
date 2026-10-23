# ADR-023: Public SDK Runtime Baselines

**Status:** Accepted (corrects `docs/sdk/architecture.md` and
`docs/sdk/distribution.md`), amended 2026-09-18 — npm SDK floor raised to
Node 22 (below)

> **Amendment (2026-09-18): the npm SDK floor is Node 22, not Node 20.**
>
> The decision below set the floor at `>=20` because Node 20 was then a
> maintained line. It is not: per the Node release schedule, **Node 20 reached
> end-of-life on 2026-04-30**. Keeping a floor at an unsupported release does
> not serve consumers — it advertises a runtime that receives no security fixes,
> and it holds our own toolchain back to match.
>
> | line | LTS from | end of life | role after this amendment |
> |---|---|---|---|
> | 20 | 2023-10-24 | **2026-04-30** | dropped — EOL |
> | 22 | 2024-10-29 | 2027-04-30 | **the floor**, and the `@types/node` line |
> | 24 | 2025-10-28 | 2028-04-30 | tested |
> | 26 | 2026-10-28 | 2029-04-30 | tested |
>
> - **Minimum supported consumer runtime: `"engines": { "node": ">=22" }`.**
> - **CI consumer matrix: Node 22, 24, 26** — the floor, the current LTS, and
>   the line that becomes LTS on 2026-10-28.
> - **`@types/node` tracks the *floor* (the 22 line), not the newest runtime we
>   test.** These are different things and conflating them is the trap this
>   amendment closes. We want the SDK *run* on Node 26 — that is why 26 is in
>   the matrix. We do not want it *compiled* against Node 26 declarations while
>   claiming Node 22 support: TypeScript would accept a global or built-in that
>   Node 22 does not have, and no green build would reveal it, because type
>   checking is the only thing that could have objected.
> - **Mechanically enforced** by `scripts/check-node-types-floor.sh`, with its
>   own negative self-test — the `@types/node` major may not exceed the declared
>   minimum Node major. Raising the ceiling now requires raising the floor
>   first, which is an amendment to this ADR rather than a dependency bump.
>
> **This is a consumer-visible contract change**, and follows the
> deliberate-bump policy the original decision sets out for both baselines: a
> consumer pinned to Node 20 must move. That is the same requirement Node itself
> already imposes on them.
>
> The JVM SDK's Java 17 baseline is **unchanged** — Java 17 remains supported
> until 2029, so the reasoning that produced it still holds. Only the Node floor
> moved, and only because its underlying assumption expired.

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
  is not a supported floor. **Superseded by the 2026-09-18 amendment above:
  the floor is now `>=22`, Node 20 having itself reached end-of-life.**
- **CI test matrix**: Node 20, 22, and 24 (kept aligned with maintained
  release lines over time). **Now 22, 24 and 26 — see the amendment.**
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
