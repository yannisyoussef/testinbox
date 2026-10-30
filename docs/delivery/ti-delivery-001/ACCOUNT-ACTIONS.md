# Human account metadata requests — M3i

Implementation under accepted ADR-036; not a deployment approval. Product
requests retain the existing workspace limits. Login delivery and provider
state retain their own budgets. This increment bounds an authenticated
account's session/admission/waitlist hot polling instead of equating a bounded
waitlist table with a bounded request workload.

The same account's sessions/nodes share 60 metadata requests per database-clock
calendar minute; all authenticated account metadata shares 3600 per minute.
No runtime/request field raises these ceilings. A refusal returns private
429 account-rate-limit-exceeded and Retry-After, without global consumption
or another participant's information. Normalized route patterns are charged
before handlers/JSON. A per-account refusal still consumes the global budget.
Logout is excluded so a throttled account can always revoke its session.

Only a real active verified session can allocate a bucket. There is one row
per account (FK cascade on deletion) and one global row; no source-IP or raw
cookie map. Counters overwrite their minute, not append a history. V18 is
expand-only; both tables are explicitly excluded from backups and recreated
by charge after a restore. This is not a defence against all unauthenticated
traffic: API/edge connection ceilings and login-specific budgets remain part
of the release boundary. No claim is made that a per-process in-memory map is
a distributed rate limit. Direct database locking by an operator remains an
infrastructure fault, not a capacity refusal.

Verification: full backend build PASS (2m41s), 1,689 tests with no failures/
errors/skips; eight new PostgreSQL cases, one HTTP journey and two writer-rule
cases. Detekt PASS, backup suite 187/187, eighteen expand-only migrations.
The complete real native browser/mail journey also passes after this increment.
New fixtures never point at a live database. No live configuration changed.
