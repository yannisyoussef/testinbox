# Threat Model

Received email content is untrusted input from arbitrary third parties (the
message sender is not necessarily the TestInbox account holder — anyone who
learns or guesses/enumerates an address can send to it).

| Threat | Mitigation |
|---|---|
| Stored XSS via HTML email body | HTML is never rendered at the primary `testinbox.email`/`api.testinbox.email` origin. Rendered only in a sandboxed iframe on an isolated origin (e.g., `usercontent.testinbox.email`) with a strict CSP (`script-src 'none'`), `sandbox` attribute without `allow-same-origin`+`allow-scripts` together, and no cookies set on that origin. See [ADR-011](../adr/0011-html-rendering-security.md). |
| Malicious/hostile HTML (obfuscation, CSS exfiltration tricks) | Sanitization pass strips/neutralizes `<script>`, event handlers, `javascript:`/`data:` URLs in dangerous contexts, and CSS-based exfiltration vectors before any rendering; raw HTML remains available via `/raw` for cases where a test intentionally asserts on the unsanitized source. |
| Tracking pixels / remote resource loading | Remote image/resource loading is not automatically performed by TestInbox's own systems; if the sandboxed viewer loads remote resources at all, it goes through a proxy that strips tracking query params and never forwards the recipient's real IP/identity — proxying vs. blocking entirely is a decision for the security-focused ADR pass at implementation time, but auto-fetch-with-no-mediation is rejected outright. |
| Malicious SVG (embedded `<script>`/`<foreignObject>`) | SVG treated as an attachment/asset like any other hostile HTML surface: never inlined into the primary origin's DOM; served with `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` when downloaded directly. |
| MIME bombs / decompression bombs | Size and nesting-depth limits enforced during parsing in the isolated `ingestion-gateway`; exceeding limits aborts parsing (message becomes `ParseFailed`) rather than exhausting memory/CPU. |
| Malformed MIME (parser exploitation attempts) | Parsing runs in a process/module boundary isolated from the API (`ingestion-gateway`), limiting blast radius; parser fed a corpus of adversarial MIME in tests (`docs/quality/strategy.md`). |
| Malicious attachments (executables, disguised types) | Not executed or previewed inline; served only as opaque downloads with sanitized `Content-Disposition` filenames; content-type sniffing disabled (`nosniff`) so a browser won't reinterpret a malicious file as HTML/script. |
| Archive bombs within attachments | Attachments are stored and served as opaque bytes; TestInbox does not automatically decompress/expand archive attachments server-side in MVP, avoiding the bomb-expansion attack surface entirely. |
| Filename attacks (path traversal, homoglyphs, overlong names) | Object storage keys are always generated attachment IDs, never derived from the sender-supplied filename; the original filename is stored as metadata only and re-escaped wherever displayed. |
| SSRF via extracted URLs | TestInbox never auto-fetches URLs found in message content; link extraction is data-only. Any future "preview a link" feature would require its own SSRF-hardened fetch path (deny-list of internal IP ranges, no redirects to internal networks) — not built in MVP. |
| Authorization bypass / cross-project resource access | Every resource fetch is authorized against the caller's workspace/project derived from their API key, never from a trusted path parameter alone; cross-tenant access is treated as a security bug (see [`docs/api/principles.md`](../api/principles.md)). |
| API key leakage | Keys are opaque, hashed at rest (never stored/logged in plaintext), scoped (least privilege via scopes), and revocable; SDKs never log the key value even at debug level. |
| Denial of service (volumetric or resource-exhaustion) | Rate limiting per API key/workspace (`docs/security/abuse-model.md`), size/nesting limits on MIME parsing, capped wait-request duration and count of concurrent waits per inbox/workspace. |
