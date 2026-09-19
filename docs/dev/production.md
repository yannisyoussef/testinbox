# Production

The contract between the application and the **production** estate: the OVH
dedicated host in France, operated through the Infinity Ops platform
([ADR-034](../adr/0034-production-platform-and-promotion.md)). It is written
the way [staging.md](staging.md) is: what the application needs, what it
exposes, what must not change — not an Ops runbook.

**Production is not deployed and not live.** What this document enables is a
*dark* deployment: exact staging-proven artifacts running on the production
host with production secrets, private SMTP only, no MX, no relay from the
edge, no tenant traffic. Public activation is TI-007 plus the human gates in
[ADR-004](../adr/0004-initial-inbound-provider-strategy.md).

## Topology

```
Internet HTTPS ──▶ Cloudflare ──▶ production ingress (Ops) ──▶ API / web
                                                                 │
                                                  ┌──────────────┼──────────────┐
                                                  │              │              │
                                             PostgreSQL     object store    ingestion (SMTP, private)
                                             (direct,       (pre-provisioned
                                              no pooler)     bucket, scoped creds)

future public SMTP ──▶ dedicated Contabo EU Postfix edge ──▶ private authenticated path ──▶ OVH ingestion
                       (dormant; TI-007)                     (does not exist yet)
```

The edge and the application host are separate trust boundaries and stay so.

## The four things a production process refuses to start without

`SPRING_PROFILES_ACTIVE=production` and `TESTINBOX_ENVIRONMENT=production`,
together. `production` is a profile **group** over the same `deployed` layer
staging uses; `application-production.yaml` holds only what is stricter, and
`DeploymentSafety` enforces it whatever an environment variable says
([ADR-034 §4](../adr/0034-production-platform-and-promotion.md)):

| refused | why |
|---|---|
| profile `production` without environment `production`, or the reverse | a staging configuration labelled production, or a production process that does not know it is one |
| `testinbox.mail-domain` ≠ `inbox.testinbox.email` | the public MX will name that domain and no other (ADR-004) |
| API without an `https://` `TESTINBOX_PUBLIC_BASE_URL` | a production API node has a public origin and must state it |
| `staging`, `rehearsal`, `localhost`, `127.0.0.1` or `.local` in the base URL, `TESTINBOX_DB_URL` or `TESTINBOX_S3_ENDPOINT` | the environment's own data services, never another environment's |
| `TESTINBOX_S3_CREATE_BUCKET=true` | production credentials hold no `CreateBucket`; the bucket is pre-provisioned |
| `TESTINBOX_EDGE_REQUEST_CEILING` unset | the ingress ceiling must be declared so the wait window is proven to fit (100 s behind Cloudflare; `TESTINBOX_WAIT_WINDOW_CAP` stays 60 s) |
| `testinbox.limits.enabled=false` | a reachable deployment would be unprotected (ADR-027) |
| a short, low-entropy or fixture bootstrap key | it is the highest-privilege row in the table (ADR-032 §8) |

Plus everything the deployed baseline already refuses: local-development
credentials, a plaintext base URL, a proxy read timeout that does not clear
the wait window by 30 s. All violations are reported at once, by setting
name, never by value.

## Readiness, and one setting the application checks live

Same probes and same components as staging
([staging.md](staging.md#health-and-readiness)), plus **`dbSession`** on the
API: it reads `SHOW idle_in_transaction_session_timeout` on every probe. In
production a disabled value (`0`, PostgreSQL's default) takes the node
**OUT_OF_SERVICE** until Ops sets it; elsewhere it is reported and the node
stays up. The recommended value is `30s` — above the 30 s claim-wait ceiling,
well below anything that stops bounding a partitioned node
([ADR-033](../adr/0033-idempotent-mutations.md), [idempotency.md](idempotency.md)).

Still unverifiable from inside the application, and therefore Ops
acceptance items rather than checks: that the database is reached
**directly** with no transaction-mode pooler (the synthetic long-poll suite
measures the symptom; `testinbox_wait_listen_degraded_polling` reports it
continuously), that the management ports are unreachable from the ingress,
and that `/actuator` is not routed (the edge synthetic asserts it).

## Object storage privileges

The bucket is created by Ops before the first deployment. The runtime
identity holds object read/write/list/delete on that bucket and nothing
else. With `create-bucket: false` the adapter never attempts creation; an
absent or inaccessible bucket is a readiness failure
(`objectStorage: DOWN, NoSuchBucketException`) and every write fails loudly,
which the storage test suite proves against MinIO. Local development and the
rehearsal keep automatic creation because the store there is theirs.

## Promotion and handoff

The full model is [ADR-034 §3](../adr/0034-production-platform-and-promotion.md).
In operation:

1. **Release pull request `develop` → `master`.** `Release candidate` runs
   three legs — candidate identity, vulnerability policy, migration rollback
   safety — and one aggregate, **`Production promotion gate`**, the context
   `master` requires. Candidate identity resolves the four digests for the
   head commit and verifies each attests to that commit from
   `build-images.yml`; it uploads `release-manifest.json`.
2. **Merge** is a human decision and *is* production approval of that source
   commit. It deploys nothing.
3. **`Production handoff`** (`workflow_dispatch`, on `master`, environment
   `production-handoff` with required reviewers) takes the candidate SHA,
   re-verifies it — merged from `develop` into `master`, artifacts exist,
   provenance binds, rollback floors — and hands the digest set to Ops with
   `TESTINBOX_ENVIRONMENT=production`. Green means **accepted**, not deployed.
4. **Ops** confirms the digest set is the one its staging reconcile proved,
   then reconciles, migrates, waits for readiness and runs the synthetics.
   The production verdict lives there.

Nothing in 1–3 builds, pushes, or holds a host credential. What GitHub asserts
is: built, attested, merged, verified. What only Ops can assert is: staging
proved these bytes; production runs them.

### Required GitHub configuration — HUMAN ACTION

| item | value |
|---|---|
| Environment `production-handoff` | deployment branches restricted to `master`; required reviewers — this restriction is what stops a branch that edits the workflow from dispatching it |
| secret `GITLAB_TRIGGER_TOKEN` on it | a trigger token for the production pipeline; can start one pipeline and nothing else |
| variable `GITLAB_OPS_API_URL` | the Ops instance API root; no fallback, or the token would be posted to gitlab.com |
| variable `GITLAB_OPS_PRODUCTION_REF` | the Ops ref whose pipeline deploys production; no fallback |
| variable `GITLAB_OPS_PROJECT` | optional; defaults to `infinity%2Finfinity-core` |
| `master` branch protection | see [release-process.md](release-process.md#master) — applied only after the gate is proven |

## Rollback

Artifact rollback is a `Production handoff` with the previous approved
candidate SHA: the same verification, the same digests it had, no rebuild.
The previous set is durably recorded twice — in the earlier handoff's run
summary and manifest artifact on GitHub, and in Ops's own reconcile record —
and Ops follows every rollback with readiness and the synthetic suite.

`deploy/rollback-floors.txt` names commits a candidate must contain. The
first is the V4 credential lifecycle (TI-002, `a2cb7ecc`): an artifact from
before it starts cleanly against the newer schema and then answers `401` to
every managed key — a technically startable rollback that is an outage for
every API client ([rollback.md](rollback.md)). The verifier refuses such a
candidate unless `acknowledge_rollback_hazard` is set, and then still warns
in the log. Database rollback is not automated and will not be.

## Backup and restore — the data-lifecycle contract

Ops owns the backup runtime. The application owns **what a backup may
contain**, because a backup that copies everything would keep message content
alive past the ADR-009 TTL and past explicit deletion, in a copy nobody
promised.

`deploy/backup/scope.txt` classifies every table; `scripts/check-backup-scope.sh`
checks a real dump (or a `pg_restore -l` table list) against it, and
`check-backup-scope.test.sh` proves it refuses content rows, a missing
control-plane table, an unclassified table, and a backup that examines
nothing.

| backed up | never |
|---|---|
| `workspace`, `project`, `api_key`, `exact_address_reservation`, `flyway_schema_history` | `inbox`, `message`, `attachment`, `idempotency_record`, `rate_bucket`, `wait_lease`; **all object storage** |

What that buys, stated plainly:

- **Disaster recovery keeps identity.** Workspaces, projects and every
  credential verifier survive; a tenant's CI keeps authenticating after a
  restore. No plaintext credential is recoverable from a backup (ADR-032 §4).
- **Retention cannot silently widen.** Nothing a sender or tenant was
  promised would be deleted exists in a backup.
- **A restore cannot dangle.** No message row is restored, so none can point
  at a raw object that was never backed up; the orphan sweep handles the
  reverse.
- **Reservations survive**, so an `EXACT` local-part in cooldown stays in
  cooldown across a restore (ADR-021).

**The restore drill** (Ops, required before dark deployment): restore the
backup into a disposable database, run the *current* migrator (it must apply
nothing), start an API node against it, authenticate with a managed key that
existed at backup time, and confirm `message` and `inbox` are empty. A drill
that checks only "restore exited 0" is not a drill.

### HUMAN DECISION REQUIRED

Not decided by this increment and not decidable by software:

| decision | options | consequence |
|---|---|---|
| **Backup retention period** for the control-plane dump | 7 days · 30 days · 90 days | Longer keeps more restore points and holds credential *verifiers* and reservation history longer; nothing in the set is message content, so the privacy cost is bounded to tenant identity and hashed credentials. Any period above the 24 h `EXACT` cooldown (ADR-021) means a restore can revive a reservation whose cooldown has since elapsed — acceptable, but say so |
| **RPO** (how much control-plane change may be lost) | daily dump · hourly dump · continuous archiving | Determines how recently created workspaces, projects and keys survive a disaster. Message content is out of scope of RPO by design |
| **RTO** | hours · one hour · minutes | Determines whether Ops needs a rehearsed, scripted restore or a documented manual one; the drill above is the minimum for any answer |

## Secrets — names, not values

| secret | held by | rotation |
|---|---|---|
| `TESTINBOX_DB_USER` / `TESTINBOX_DB_PASSWORD` | Ops | rotate at the database, then the stack; no API-visible effect |
| `TESTINBOX_S3_ACCESS_KEY` / `TESTINBOX_S3_SECRET_KEY` | Ops, scoped to the bucket, **no `CreateBucket`** | rotate at the store, then the stack |
| `TESTINBOX_BOOTSTRAP_API_KEY` | Ops; break-glass only ([api-keys.md](api-keys.md#bootstrap-and-the-first-key)) | change the value and restart; retires the previous one on the next request |
| production synthetic credential | Ops; a **managed** key with `inboxes:write`, `messages:read` only | revoke and mint through the API; never the bootstrap key |
| production administrative credential (`api-keys:manage`) | Ops; **distinct** from the synthetic one, never on a runner | as above |
| `GITLAB_TRIGGER_TOKEN` (production) | GitHub `production-handoff` environment | rotate in GitLab, update the environment |
| TLS / origin certificate | Ops, at the edge | not application-owned |

Rules the repository enforces or proves: no secret in Git (gitleaks), none
in an OCI label, build arg or layer (the Dockerfiles carry only source
repository, revision and version), none in a workflow output or step summary
(the handoff tests assert the token is never printed), and every startup
refusal names the setting, never the value.

## Observability — the alerting contract

Production has no Prometheus, Grafana or Loki yet; this repository builds
none. It states what must be observed, over metrics that already exist on the
private management ports (`docs/architecture/observability.md`), plus Spring
Boot's standard binders, whose presence the rehearsal asserts:

| signal | source | alert when |
|---|---|---|
| process liveness | `/actuator/health/liveness` | not `UP` |
| application readiness | `/actuator/health/readiness` per deployable | not `UP` for > 1 min; inspect `schema`, `objectStorage`, `waitNotifier`, `dbSession`, `smtpListener` |
| deployment identity | `testinbox_build{service,git_sha}` | `git_sha` ≠ the handed-off candidate |
| schema compatibility | readiness `schema` | `OUT_OF_SERVICE` — migration did not run or history holds a failure |
| object store / database reachability | readiness `objectStorage`, `db`; `testinbox_object_storage_operation_duration_seconds{outcome="FAILURE"}` | any `DOWN`; failures > 0 sustained |
| LISTEN degraded | `testinbox_wait_listen_degraded_polling` | `== 1` for > 1 min — **the one that is otherwise invisible** (everything else stays green) |
| inbound SMTP | `testinbox_smtp_accept_total`, `testinbox_smtp_reject_total{reason}` | reject rate rising; accepts flat while the edge queue grows (TI-007) |
| unknown-recipient discards | `testinbox_smtp_unknown_recipient_discard_total` | rate change — an enumeration attempt or a misrouted sender |
| ingestion rate refusals | `testinbox_rate_decision_total{category="INGEST",outcome="REFUSED"}` | sustained refusals on one workspace |
| wait latency | `testinbox_wait_request_duration_seconds{outcome}` | p99 of `MATCHED` above 1 s while degraded polling is 0 |
| HTTP errors | `http_server_requests_seconds_count{status=~"5.."}` | 5xx ratio > 1 % over 5 min |
| saturation | `jvm_memory_used_bytes`, `hikaricp_connections_active/pending`, `process_cpu_usage` | pool pending > 0 sustained; heap > 85 % of max |

Every label is a closed enum; no metric carries an API key, address, subject,
local-part, body, or identifier. **The scrape endpoint is a trust boundary**:
an environment that exposes `/actuator/prometheus` more widely than its SMTP
listener has weakened ADR-025.

## Origin isolation

Required: the approved ingress path reaches TestInbox; a direct connection to
the origin's own address does not. The repository does not own the host
firewall and holds no OVH address. It ships the invariant as
`deploy/synthetic/origin` (`npm run test:origin`), run from **outside** the
host with the address supplied by Ops at run time, with a mandatory positive
control through the public hostname. The result is Ops acceptance evidence;
the `DOCKER-USER` chain being non-empty is not.

## Synthetics for a dark deployment

Run by Ops after the production reconcile, from the paths that can reach
each target. No public Postfix edge is required or exercised.

| suite | path | proves |
|---|---|---|
| `npm test` (deployment gate) | on the host: public HTTPS + private SMTP | create, deliver, wait, retrieve, cleanup; a full 60 s window through the real ingress; a parked wait woken by LISTEN; edge invariants incl. `/actuator` not routed and unknown Host refused |
| `npm run test:product` | on the host, with the administrative credential | credential lifecycle, idempotency |
| `npm run test:identity` | on the host, management ports | both deployables run the approved commit and are ready; LISTEN live; DB session bounded and **enforced** |
| `npm run test:origin` | from outside | direct-origin isolation |

## Known limits of this contract

- A second API node re-opens the readiness cascade ADR-030 flags: shared
  dependencies in readiness remove every node at once. Revisit before any
  multi-node production.
- A managed database must expose a session-mode endpoint; the pooled kind
  breaks LISTEN silently (ADR-030 capability 2).
- Self-service signup invalidates the "workspaces are operator-created"
  assumption behind the idempotency-record bound (ADR-033) and changes the
  backup-volume picture.
- Retention, RPO and RTO are undecided (above).
