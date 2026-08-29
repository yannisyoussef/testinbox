# TestInbox — Product

This document indexes the product-definition material. See
[`VISION.md`](VISION.md) for the overall vision, non-goals summary, and MVP.

- [Personas](docs/product/personas.md)
- [Primary use cases](docs/product/use-cases.md)
- [Domain model](docs/product/domain-model.md)
- [Non-goals](docs/product/non-goals.md)

## Product principles

1. **Automation-first.** Every capability must be usable headlessly, from an
   SDK or REST call, without a human in the loop. The dashboard is diagnostic
   tooling, not a required step in any workflow.
2. **Deterministic over convenient.** Timing-sensitive operations
   (`waitForMessage`) must have an explicit, documented contract rather than
   "usually works." See [`docs/architecture/wait-semantics.md`](docs/architecture/wait-semantics.md).
3. **Ephemeral by default.** Inboxes and messages are not meant to persist
   indefinitely; TestInbox is not an archive.
4. **SDKs are product, not glue.** SDK ergonomics inform REST API shape from
   day one — see [`docs/sdk/`](docs/sdk/).
5. **Provider-agnostic core.** Inbound mail infrastructure (SMTP, SES, future
   providers) stays behind an abstraction; the domain model never encodes a
   specific provider's concepts.
