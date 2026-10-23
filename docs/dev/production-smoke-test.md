# Dark production deployment — the smoke-test contract (TI-007)

What Ops execute after the first production deployment, **before** the owner
declares it verified and **without** any public MX or public TCP/25. Every
check reuses an existing suite under `deploy/synthetic/`; nothing here is a
new framework. The deployment report (§4) names the exact source commit and
the four image digests, and attaches the JUnit reports.

The release is deployed with `TESTINBOX_STORAGE_ENFORCEMENT=OFF` by owner
decision (`docs/releases/0.1.0.md`). That is an explicitly limited mode: the
ADR-035 protocol runs and is observed, no storage ceiling refuses mail, and
check 17 proves the mode did not change.

## 1. Preconditions

- The candidate was handed off by `Production handoff` on `master` with the
  approved SHA, and `infinity-core` reports the reconcile converged
  (`docs/dev/production.md` § Promotion and handoff). A green GitHub workflow
  only means Ops accepted the request.
- The host runs the synthetic suite with the **synthetic** credential, never
  the bootstrap key; the product suite needs the administrative credential
  for its minutes of use and nothing else (`deploy/synthetic/README.md`).
- Row M of `docs/dev/production-ops-acceptance.md` holds: no MX record, no
  public 25, the edge transport still `discard:`.

## 2. The checks, in order

| # | What | How (reuse) | Pass |
|---|---|---|---|
| 1 | Database migrations ran, once, by the migrator only | the migrator's exit code and log in the reconcile; `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank` shows every bundled version succeeded and nothing after it; the API and ingestion logs show `spring.flyway.enabled: false` | every bundled migration `success = true`; schema version equals the artifact's bundled head (readiness would otherwise be DOWN: ADR-029) |
| 2 | API readiness | `npm run test:identity` (`api: readiness is UP and every required component reports`) against the private management port | UP with `db`, schema, storage and `waitNotifier` components |
| 3 | Ingestion readiness | `npm run test:identity` (`ingestion: the SMTP listener is up`, readiness UP) | UP |
| 4 | Object-storage readiness | part of 2 and 3 (`storage` component), plus the first full workflow (7–10) writing and reading a raw object | the component is UP and check 9 returns the bytes |
| 5 | Authentication | `npm test` (`the deployment enforces authentication`), `npm run test:product` (managed credential authenticates; a revoked one stops; the deployment runs on managed credentials, not the bootstrap one; the secret is never returned) | all pass |
| 6 | Inbox creation and idempotency | `npm test` (`the full inbound workflow…`, `a retried create returns the same inbox…`, `the same key with a changed request is refused…`) | all pass |
| 7 | Private SMTP delivery | `npm test` — the synthetic sender delivers through the environment's private SMTP path (`TESTINBOX_SMTP_HOST`); `npm run test:edge` when the mail edge is deployed (tenant, unknown and postmaster recipients indistinguishable; one `DATA` to two tenants delivers to both; an oversized message refused at the edge) | all pass; `testinbox_smtp_accept_total` moves only for the synthetic sender (row M) |
| 8 | Message parsing | `npm test` full workflow: the delivered message's `subject`, `from`, text body and extracted links are asserted by the suite | pass |
| 9 | Raw MIME retrieval | `GET /v1/inboxes/{id}/messages/{messageId}/raw` for the message of check 8, with the synthetic key: the body is the bytes the sender sent (the suite's full workflow fetches raw; if the deployed suite version does not, run the one `curl` and compare the sha256 with the sent message) | byte-identical |
| 10 | Attachment retrieval | send the suite's attachment fixture (or any message with one attachment) and `GET …/attachments/{attachmentId}` | the attachment's bytes and `contentType` match what was sent |
| 11 | Deterministic wait | `npm test` (`longpoll.test.mjs`: a parked wait woken by SMTP; the full window through the ingress; a wait that finds nothing times out cleanly) | woken within the delivery latency, not at the window's end; the full window returns `200 TIMEOUT`, never a proxy `504` |
| 12 | Inbox deletion | `npm test` (the workflow deletes its inbox); then `GET /v1/inboxes/{id}` → `200` with `state: DELETED` until the sweep, `404` after it; `POST …/messages/wait` → `410` | as stated |
| 13 | TTL cleanup | create one inbox with the shortest TTL the API accepts, wait past TTL + grace, then `GET` it, `POST …/messages/wait` on it, and list its messages; `SELECT count(*) FROM inbox WHERE id = …` after the sweep interval | `GET` → `200` with `state: EXPIRED` then `404`; the wait → `410`; the list serves nothing; then no row; `testinbox_inbox_expired_total` incremented |
| 14 | API isolation | `npm test` (`GET /v1/workspace/storage answers the caller's own figures, and nothing global`); `npm run test:origin` from outside the host (the origin answers nothing directly); `npm run test:edge`/`edge.test.mjs` (actuator not routed, unknown Host refused, security headers) | all pass; a second workspace's key, when one exists, cannot read the first's inbox (`404`) |
| 15 | Metrics and error reporting | `npm run test:identity` (`the scrape endpoint names the same build`); scrape the private port: `testinbox_build{git_sha}` equals the candidate, `testinbox_storage_enforcement_mode{mode="off"} == 1`, `testinbox_wait_listen_degraded_polling == 0`, `testinbox_storage_admission_latched == 0`, `testinbox_storage_breaker_open == 0`; one deliberate bad request returns an RFC 7807 body with a `correlationId` that appears in the API log | as stated |
| 16 | Restart behaviour | restart the API, then the ingestion container, one at a time; readiness returns within the grace period; a wait parked across the API restart ends `200 TIMEOUT` or `MATCHED`, never a transport error at the edge; `npm test` passes again | as stated; no orphaned reservations after one cleanup pass (`testinbox_storage_reservations{state}` returns to 0) |
| 17 | Enforcement remains OFF | the containers carry no `TESTINBOX_STORAGE_ENFORCEMENT` (or `OFF`), `TESTINBOX_STORAGE_QUALIFICATION_LAB_RECORD_PATH` is absent, `testinbox_storage_enforcement_mode{mode="off"} == 1` on both deployables, and `npm test` (`storage.test.mjs`: an opt-in wait on a fresh inbox ends `TIMEOUT`, no refusal recorded) | as stated |

Checks 2–8, 11, 12, 14, 15 and 17 are the existing gate (`npm test`,
`npm run test:identity`) and the product suite; 9, 10, 13 and 16 are the
manual steps this document adds for the first production deployment only.

## 3. What a failure means

- 1–4: the artifact or the environment is wrong; do not proceed, roll back
  by re-handing the previous digest set (`docs/dev/rollback.md`; for the
  first deployment there is none — the environment stays dark until fixed).
- 5, 14: a security boundary is open; the owner is told before anything else.
- 7, 11: the wait or delivery path is degraded; check
  `testinbox_wait_listen_degraded_polling` (a transaction-mode pooler in
  front of PostgreSQL is the usual cause, ADR-020) and the proxy read timeout
  versus the wait window (ADR-030).
- 17: an enforcing mode on production is a misconfiguration; the process
  should have refused to start (A2.5/A2.7), so a running enforcing mode is an
  incident.

## 4. The deployment report

One file in Ops's evidence store, named for the candidate:

- source commit (`git_sha` from `testinbox_build`), the four image digests
  (api, ingestion, web, migrator) exactly as the handoff manifest lists them;
- the JUnit reports of `npm run test:junit`, `test:identity:junit`,
  `test:product:junit`, `test:edge:junit` and `test:origin:junit`;
- the manual checks 1, 9, 10, 13 and 16 with their commands and outputs;
- the enforcement evidence of check 17;
- the row-M evidence (no MX, no public 25).

The owner declares the deployment verified from this report, not from the
GitHub workflow.
