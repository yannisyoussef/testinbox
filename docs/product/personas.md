# Personas

1. **SDET / QA automation engineer** — writes end-to-end tests that exercise
   signup, password reset, or notification flows and needs to assert on the
   resulting email without touching a real mailbox. Primary interface: JVM
   or TS SDK inside their existing test framework.
2. **Backend/full-stack developer** — writes integration tests for a feature
   they're building (e.g., "does this endpoint trigger a welcome email with
   the right template"), often locally before CI. Primary interface: SDK,
   run against a local TestInbox instance (docker-compose).
3. **CI pipeline** (non-human but a first-class consumer) — creates and tears
   down inboxes as part of automated test suites, at higher concurrency and
   turnover than an individual developer. Cares about determinism, rate
   limits, and TTL/cleanup behaving predictably under parallel test workers.
4. **Platform/DevOps engineer** — administers workspaces, projects, API keys,
   and quotas; investigates flaky tests via the dashboard; is the primary
   user of the web UI and observability surfaces.

Explicitly **not** a persona: a human wanting a disposable personal inbox to
sign up for a newsletter anonymously. Optimizing for that persona would pull
the product toward YOPmail-style behavior, which is a stated non-goal.
