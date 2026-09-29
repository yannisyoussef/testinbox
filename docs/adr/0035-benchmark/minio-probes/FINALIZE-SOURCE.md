# When can a fully received PUT still commit? A source reading of the pinned MinIO

This is a source reading of MinIO tag `RELEASE.2025-04-22T22-12-26Z` (commit `0d7408f`), the release the GHCR
mirror pins. It uses Go toolchain 1.24.2's `net/http/server.go`, the toolchain that release builds with. The
reading was done on 2026-09-29 for ADR-035 §9 (TI-DEC-001a §2).

The deployment analysed is the reference topology: single node, single drive (`minio server /data`, which is
erasure "SD" mode), default configuration, no `MINIO_*` timeout overrides, and plain HTTP.

All file:line references are at that tag. **Source-verified** means read in the source. **Inferred** means
reasoned from the source, but not tested.

## Verdict

**There is no deterministic, server-enforced upper bound on the time from "body fully received" to "committed or
discarded".**

What the server *does* enforce is a bound on when the final commit may **begin**. Nothing bounds how long it takes
to **finish** once it has begun.

## The commit can begin only while the request context is live (source-verified)

- **The last cancellation check.** `xlStorage.RenameData` checks `contextCanceled(ctx)` at `cmd/xl-storage.go:2891`.
  That is immediately before the final commit, `renameAll(tmp/xl.meta → bucket/object/xl.meta)` at `:2896`.
  - Earlier checks sit at `:2832`, `:2859` and `:2877`.
  - The namespace-lock wait also stops on `ctx.Done()` (`internal/lsync/lrwmutex.go:108-115`,
    `cmd/namespace-lock.go:258`).
  - If any of these checks sees the context cancelled, the deferred tmp delete removes the upload
    (`cmd/erasure-object.go:1385`), and nothing becomes visible.
- **What cancels the context** (Go 1.24.2 `net/http/server.go`):
  - `registerOnHitEOF(req.Body, startBackgroundRead)` at `:2089`: once the body reaches EOF, a background read
    watches the connection.
  - `backgroundRead` at `:689-722`: any read error other than an aborted timeout calls `handleReadError` →
    `cancelCtx()` (`:757-758`).
  - So a client that **closes or resets** the connection after the body cancels the context at once.
  - **A client that stays connected but silent is *not* cut off.** This was corrected on 2026-09-29, after the
    focused storage re-review. At body EOF, Go calls `rwc.SetReadDeadline(time.Time{})` (`server.go:685`)
    before starting the background read. MinIO's `DeadlineConn.SetReadDeadline` records a zero deadline as
    `infReads=true` (`deadlineconn.go:133`), and from then on skips every per-read deadline
    (`deadlineconn.go:56`). So the background read blocks indefinitely, and the request context stays live for
    as long as the client keeps the connection open.
    - MinIO's idle timeout only bounds reads **before** EOF: a body that stalls mid-way errors out, and nothing
      commits.
    - This is verified in source, but **not reproduced** by the qualification. In `v3-freeze*-W`, the 30 s drive
      deadline abandoned each pre-commit upload during a 45–120 s freeze, so none committed after the thaw
      (`QUALIFICATION.md`). ADR-035 does not rely on that deadline.
  - Only two things cancel the context once the body has ended:
    - the client closing or resetting the connection;
    - the connection dying. MinIO sets `TCP_USER_TIMEOUT` (10 min by default, `MINIO_CONN_USER_TIMEOUT`;
      `internal/http/dial_linux.go:93`, `cmd/server-main.go:112`) and keepalive of 15 s / 15 s / 5 on its
      listener, so a dead peer host is torn down after roughly 15 s + 10 min (inferred from the socket options;
      corrected from an earlier "150 s", per the focused re-review).

    A frozen *process* on a live host keeps the connection alive.

## Nothing bounds how long the commit takes to finish (source-verified)

- **The final commit is a plain `rename(2)`** (plus `mkdirAll` and a retry) at `xl-storage.go:2896`. It does not
  observe the context. Once started, nothing in MinIO stops it.
- **The drive deadline abandons the operation; it does not cancel it.**
  - `xlStorageDiskIDCheck.RenameData` (`cmd/xl-storage-disk-id-check.go:481-503`) wraps the call in
    `xioutil.WithDeadline(GetMaxTimeout())` (30 s by default, `internal/config/drive/drive.go:30,36-41,73-92`).
  - `WithDeadline` (`internal/ioutil/ioutil.go:121-138`) runs the work in a goroutine, and returns `ctx.Err()` when
    the timer fires. **The goroutine, and the syscall inside it, keep running.**
  - So PutObject can fail (lock released, tmp delete fired) while the rename still completes later. The object
    then appears whenever the kernel finishes the syscall.
  - On a hung filesystem, a blocked `rename(2)` can stay in uninterruptible I/O indefinitely. That is inferred from
    Linux semantics.
- **Rollback only covers drives that already reported success.** `renameData`'s undo (`erasure-object.go:1055-1073`)
  applies only to drives that returned success, so a timed-out drive is never undone.
- **An earlier wait also has no timeout.** `closeBitrotWriters` (`bitrot-streaming.go:90`) waits, with no timeout,
  for `CreateFile(context.TODO(), …)` (`:130`), including its final `Fdatasync` (`xl-storage.go:2195`).
  - That wait does not observe the context. The context check that follows it still prevents a *late start*.
  - But nothing bounds the time taken to reach that decision.

## The only hard bound: a process restart (source-verified)

On startup, `.minio.sys/tmp` is renamed to `tmp-old/<uuid>` and removed (`cmd/prepare-storage.go:74-108`). A process
that has exited cannot commit what it left in tmp. The exception is a `rename(2)` that the kernel was already
executing; per Linux semantics (inferred), it completes or fails regardless of the process.

## Multi-drive erasure is worse (inferred)

The same path runs once per drive, in parallel.

- **Late renames can outlive a failed write.** When write quorum fails, only the drives that succeeded are undone.
  Timed-out drives can finish their rename later.
- **Example:** with 4 drives at EC:2 (write quorum 3), two drives succeed and two time out. The two successes are
  undone, then the two late renames finish. That leaves `xl.meta` on 2 drives, which meets read quorum. So an
  object can become readable after the client was told the PUT failed.
- **A qualification for single-drive SD mode therefore does not carry over to erasure-coded deployments.**

## Timeouts and settings (defaults at this tag)

| Setting | Default | Effect on "body received → commit" |
|---|---|---|
| `MINIO_IDLE_TIMEOUT` (`--idle-timeout`, hidden) | 30 s | Bounds reads **before** body EOF only: a stalled body errors out. After EOF it bounds nothing (see above). 0 disables the pre-EOF deadlines as well. |
| `MINIO_READ_HEADER_TIMEOUT` | 30 s | Headers only. |
| `MINIO_SHUTDOWN_TIMEOUT` | 30 s (deprecated) | Graceful shutdown only. |
| `MINIO_CONN_USER_TIMEOUT` (TCP_USER_TIMEOUT) | 10 min | Socket-level. Its effect on cancellation is inferred, not verified. |
| `MINIO_API_REQUESTS_DEADLINE` | deprecated, no effect | There is no per-request deadline. |
| `MINIO_DRIVE_MAX_TIMEOUT` (`drive max_timeout`) | 30 s (min 1 s) | When the caller *stops waiting* on a drive operation. It does **not** cancel the operation. |
| Namespace-lock wait | dynamic, 10 min (min 5 min) | Waiting only. It also exits on context cancellation. |
| `MINIO_API_STALE_UPLOADS_EXPIRY` / `…_CLEANUP_INTERVAL` | 24 h / 6 h | Space reclamation of `tmp/<uuid>`. Nothing becomes visible. |
| Storage class / inline threshold | release defaults | Whether data commits through `xl.meta` alone or also through a data-directory rename. |
| TLS or a proxy in front of MinIO | none in the reference topology | Changes when the client's disconnect reaches MinIO (inferred). |

## What this means for ADR-035

C_max cannot come from a server-enforced bound (TI-DEC-001a outcome A). It is split into two terms:

- **When a commit may begin: there is no server bound after EOF.** The writer's RST at `T_put` cancels the context.
  - A writer that freezes mid-body is cut off by the pre-EOF read deadline (idle timeout, about 30.25 s), and nothing
    commits.
  - Once the body is complete, the commit does not wait on the client at all. It is delayed only by storage, CPU or
    lock progress. ADR-035 gates releases on a storage liveness witness plus a drain margin for exactly that case.
- **`C_max`: how long a commit that has begun may take to complete.** This is `rename(2)` latency on the data
  filesystem. It is **not** server-bounded, so it is qualified empirically (`QUALIFICATION.md`), narrowed by
  ADR-035's storage-liveness witness, and made part of the storage compatibility contract (ADR-035 §9a).
