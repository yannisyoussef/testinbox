# Security Policy

TestInbox receives arbitrary third-party email content on behalf of its
users. Treat all message content (headers, HTML, attachments) as untrusted
input. See [`docs/security/threat-model.md`](docs/security/threat-model.md)
and [`docs/security/abuse-model.md`](docs/security/abuse-model.md) for the
full threat model and abuse-prevention design.

## Key security invariants

- Received HTML is never rendered at the primary TestInbox origin; it is only
  rendered inside a sandboxed, isolated-origin iframe with a strict CSP and no
  automatic remote resource loading (see
  [ADR-011](docs/adr/0011-html-rendering-security.md)).
- TestInbox does not automatically fetch URLs extracted from message content
  (SSRF prevention). Extracted links are data, not actions.
- Inbox creation always requires an authenticated, scoped API key. There is
  no anonymous or unauthenticated inbox creation path.
- TestInbox does not send email on a user's behalf; it is inbound-only. It is
  not an open relay.
- All resource access is scoped to a workspace/project; cross-tenant access
  is a security bug, not a missing feature.

## Reporting a vulnerability

Once this project has a public issue tracker and maintainer contact, this
section will be replaced with a disclosure process (private reporting
channel, expected response time, supported versions). No such channel exists
yet during the inception stage.
