# Thirty-account storage runtime (ADR-037)

This is application implementation and a release procedure, not permission to
activate it. Production's existing ALL/TENANT_LIMITS startup refusals remain.
No committed environment enables those modes. Registration stays closed until
the separate release, lifecycle/restore and Ops gates are resolved.

## Limits and accounting

Every participant workspace has a durable, non-recyclable 128 MiB payload /
256 MiB conservative physical-footprint share. The original owner/monitoring
workspaces require explicit finite legacy shares; they do not consume seats.
The fixed thirty shares are reserved before the first admission. No overcommit
or allocation based on the current number of participants is permitted.

T1 reads payload, object counts, every reservation state, own cleanup charge
and the internal global pool in one PostgreSQL statement under the existing
(35,1) lock. The effective payload limit is the smaller of the configured
workspace limit and its persisted share. The inbox limit is its configured
fraction of that limit. Footprint uses the existing qualified F(bytes,objects)
model, including live data, reserved writes and attributed cleanup. Full
original envelope positions still consume conservative global prefix room.

Storage refusal order: inbox payload, workspace payload, workspace footprint,
service capacity. SMTP continues returning uniform 250 for tenant quota
refusals; refused content is discarded, without a quota DSN. Infrastructure
failure answers uniform 451 before recipient resolution. Diagnostic refusal
counts/reasons remain private to that workspace; the accepted cross-event
capacity inference risk in ADR-037 is unchanged.

Deleting rows does not immediately free the physical share. V20 attributes
message/attachment cascades, releasing reservations and canonical orphan keys
to a transient workspace cleanup mirror. The mirror survives global debt
compaction into nonempty MinIO trash. Finite charges are released only by a
later, fresh, correctly ordered zero-trash observation from the approved
monitor, with matching filesystem identity, a valid watermark and trusted
counts. Pending, late, unknown or failed evidence never frees a share. An
already-mounted filesystem is not unmounted by this application.

Historical unidentifiable debt is charged to an explicitly reserved global
allowance; observed trash has its own allowance. Unknown legacy workspaces,
usage beyond a saved allocation, configuration mismatch, stale observations,
incompatible nodes or exhausted shared allowances fail the pool closed.

## Operator preparation (offline API artifact)

Use the reviewed API jar and a private JSON file containing:

- `declarations`: the exact `StorageDeclarations` used by API and ingestion,
  including ALL, G, optional Q, process surge maximum, inbox share, complete
  backend identity, expected API/ingestion node sets and filesystem budgets.
- `legacy`: an explicit list of `{workspaceId,payloadBytes,footprintBytes}`
  for every existing nonparticipant workspace (including owner/synthetics).
  Retired legacy allocations stay in this list; no shrink or implicit removal.
- `unattributedAllowance` and `trashAllowance`: explicit nonnegative bytes.

The declarations are facts/budgets from the reviewed Ops activation packet,
not numbers inferred from the 48 GiB image. Reserve all thirty participant
shares, all legacy shares, the worst full envelope, finalization, debt, trash,
probe, metadata and operational headroom. The application checks arithmetic;
Ops still verifies the actual host/MinIO combination and qualified evidence.

Set `TI_BUDGET_JDBC_URL`, `TI_BUDGET_USER`, `TI_BUDGET_PASSWORD` through the
operator's normal secret mechanism. The credential is the schema owner or an
explicitly reviewed equivalent; runtime API/SMTP roles have SELECT only on
the plan, legacy shares and cleanup mirror. Never put credentials in arguments,
a URL with userinfo, source control or a pasted transcript. Tag the JDBC
session `ApplicationName=ops:cohort-budget` for the activation allowlist.

```
java -jar api.jar storage-budget reserve /private/reviewed-pool.json
java -jar api.jar storage-budget check /private/reviewed-pool.json
java -jar api.jar storage-budget revoke /private/reviewed-pool.json
```

These commands start no HTTP server, scheduler, login provider or migration.
`reserve` requires admissions and `capacity_ready` closed, then locks the
cohort row and global T1 lock in that order. It writes the candidate pool in
one transaction, reads the same live global snapshot as T1 and rolls back if
inventory, capacity, nodes or observations do not match. Existing shares may
only remain or increase within the complete capacity proof. No command opens
admissions, changes enforcement or clears a latch. `check` is read-only.
`revoke` invalidates approval, retaining reservations and the node fence.

After reservation, the existing capacity/admission switch still needs the
separate release authorization. Even with both flags manually true, the
admission use case requires the live approved, matching ALL-mode pool.
Existing participants can log in while new admissions are paused.

## Upgrade, restore and rollback boundaries

1. Stage the candidate backup scope AND checker before running migrator.
   V19 markers and V20 legacy allocations are mandatory durable control plane;
   V21 pool approval and V20 cleanup charges are excluded transient evidence.
2. Apply migrations once with the migrator, then the candidate role grants.
   API/SMTP cannot enlarge or erase shares, write approval or release charges.
3. Deploy the reader that knows WORKSPACE_FOOTPRINT_LIMIT before any writer
   can persist it. Record the semantic rollback floor before activation.
4. V21 node registration/heartbeat is fenced with T1. Old-capability or OFF
   nodes with participants, and mismatching nodes with a saved pool, set the
   existing admission latch. No automatic latch clearing. A restore never
   restores browser sessions, cleanup evidence or capacity approval. Reconcile
   storage, deleted accounts/revocations and external rollback floors before
   considering admission; a database restore is not authorization recovery.
   A restored database stays account-quarantined until `account-lifecycle
   restore-reconcile` runs ([restore procedure](../delivery/ti-delivery-002/LIFECYCLE.md#restore-procedure)).
5. For an enforcing rollout failure, pause new admissions and ingress using
   Ops' hold-first emergency procedure. Preserve queued mail. Do not remove
   markers/shares, clear a latch or start an old unenforcing artifact as an
   automatic rollback. Requalification under OFF is a reviewed recovery window.

## Private API, SDK and console

`GET /v1/workspace/storage` (and its human alias) adds optional `footprint`:
`limitBytes`, `usedBytes`, `pendingCleanupBytes`, `availableBytes`, `overLimit`,
`enforced`. Payload remains `storedBytes + reservedBytes`. Pending cleanup is
already included in physical `usedBytes`, never added twice. These are
conservative charge bounds, not per-directory disk measurements. No global
figure is serialized. Inbox payload availability is constrained by its own
and its workspace's payload headroom; physical availability is shown separately.

Footprint refusal diagnostics use `WORKSPACE_FOOTPRINT_LIMIT` and
`quota=STORED_FOOTPRINT`, with only the caller's limit/current charge. SDKs
preserve forward-compatible reason strings and validate the optional footprint
shape. The console shows payload, physical charge, cleanup and enforcement
separately, and explains uniform SMTP acceptance/discard behavior. An older
response without footprint data never implies enforcement.
