# ADR-011: HTML Rendering Security

**Status:** Accepted

## Context

Received HTML is attacker-controlled by definition (sender is not
necessarily trusted). The web dashboard must display it without becoming a
stored-XSS vector against `testinbox.email`.

## Decision

Received HTML is never rendered on the primary origin. It is rendered only
inside a sandboxed `<iframe>` served from an isolated origin (e.g.,
`usercontent.testinbox.email`) with: a strict CSP (`script-src 'none'`),
`sandbox` attribute excluding the `allow-same-origin`+`allow-scripts`
combination together, no cookies/session state on that origin, and remote
resource loading blocked or proxied (not auto-fetched with the recipient's
context) — see `docs/security/threat-model.md`.

## Alternatives considered

- **Sanitize-and-render inline on the primary origin**: rejected — sanitizer
  bypasses are a recurring, well-documented class of vulnerability; even a
  well-sanitized render inline on the primary origin risks cookie/session
  theft if any bypass is later found. Isolated-origin sandboxing bounds the
  blast radius structurally, not just via sanitizer correctness.

## Consequences

- Dashboard architecture must provision a second origin/subdomain
  specifically for untrusted content rendering.
- Any future "send me a preview screenshot" or similar feature must go
  through the same isolated-rendering path, not a shortcut.
