# ADR-004: Initial Inbound Provider Strategy

**Status:** Proposed

## Context

MVP must run entirely locally (no cloud dependency). Production eventually
needs a durable inbound provider (self-hosted Postfix vs. AWS SES).

## Decision (proposed)

Build a local SMTP adapter first (sufficient for MVP and CI, no cloud
dependency). Defer the choice between self-hosted Postfix and AWS SES for a
production-grade provider until a target deployment environment is decided
— this is listed as a Human Decision Required in `VISION.md`, since it
depends on operational ownership (self-hosted mail infra vs. relying on
AWS) that is a business decision, not purely technical.

## Alternatives considered

- **Build SES adapter first**: rejected for MVP — introduces an AWS
  dependency that contradicts the "runnable entirely locally" MVP
  requirement, and SES receiving setup (domain verification, SNS/Lambda
  wiring) is nontrivial ceremony to front-load before the core value
  proposition is validated.

## Consequences

- MVP has no path to receiving real internet-originated mail yet — only
  mail from a system-under-test configured to point at the local adapter.
  This is acceptable because the primary use case (SUT → TestInbox in a test
  environment) does not require internet-facing mail receipt.
- This ADR cannot be marked `Accepted` for the production provider choice
  until the relevant Human Decision is made.
