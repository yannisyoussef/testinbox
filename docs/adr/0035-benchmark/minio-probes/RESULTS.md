# MinIO behaviour probes (ADR-035 §4, §6, §6a, §9)

These probes ran on 2026-09-29 against `quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z`. That is the release
the GHCR mirror pins (arm64 member, the same bytes as the mirror). The client was boto3 1.43.104 plus raw
sockets. For every raw-socket PUT, the scripts sign SigV4 query ("presigned") URLs themselves, with a clock
they control. The server and host clocks agreed to the second, checked with `date -u` in the container and on
the host.

| # | Probe | Result | Meaning for ADR-035 |
|---|---|---|---|
| E1 | SDK `put_object`, 15 MiB | one object; `ListMultipartUploads` empty | A plain PUT is single-part. |
| C0 | control: presigned 300 s, immediate PUT | `200`, object present | The presigner is correct, so the refusals below are real. |
| C1 | control: signed 60 s in the past, expires 300 s | `200` | A signing clock behind the server is fine while unexpired. |
| E2 | PUT aborted mid-body (5 of 15 MiB, then the socket closed) | no object, no multipart upload | An incomplete single PUT leaves no object. |
| E3 | presigned 3 s; request **starts** at 5 s | `403 AccessDenied`, no object | Expiry is enforced by the server: this is the fence. |
| E4 | presigned 3 s; headers sent at 0 s, body trickled until 8 s | **`200`, object present** | Expiry is checked at request **start** only. |
| E5 | full 4 MiB body sent; the client closes without reading the reply | **object present** | A "failed" PUT (from the client's view) can still create an object. |
| E6 | signed with a clock 10 min **ahead**, expires 60 s | **`200`** | MinIO accepts a future `X-Amz-Date` within its 15 min skew, so a fast signing clock extends the fence. |
| E7 | signed 20 min ahead | `403` | The skew allowance is bounded (15 min). |
| E8 | signed 5 min in the past, expires 120 s | `403` | Expired is expired, whoever signed it. |
| Q1 | 2 MiB object into a bucket with a 1 MiB hard quota | **`400 XMinioAdminBucketQuotaExceeded`** | The quota refusal is a **4xx**, and it is definitive: no object. |
| Q2–Q7 | six 400 KiB objects into the same 1 MiB bucket | **all `200`**; 2.4 MiB stored | The bucket quota is checked against lagging usage, so it is not a hard fuse. |
| E9a | control: signed `Content-Length` = N, sent N | `200` | — |
| E9b / E9c | signed N, sent N+1 / N−1 | **`403 SignatureDoesNotMatch`**, no object | The size binding is enforced: a URL creates exactly the reserved size. |
| E9d | signed N, sent chunked | **`411 MissingContentLength`**, no object | Chunked encoding cannot get around the binding. |
| E9e | length **not** signed, sent 1 500 bytes | `200`, 1 500-byte object | The binding exists only if `content-length` is a signed header. |
| M1 | `ListMultipartUploads`, whole bucket, with one open upload | finds it | A bucket-wide guard works. |
| M2 | `ListMultipartUploads(prefix = parent "ws/inbox/msg-1/")` | **empty** | MinIO does not list incomplete uploads by parent prefix. A prefix-based absence proof would pass vacuously. |
| M3 | `ListMultipartUploads(prefix = exact key)` | finds it | The absence proof must list by **exact reserved key**. |
| M5 | `ListObjectsV2(parent prefix)` | does not show the incomplete upload | Listing objects alone is not an absence proof. |
| I1 | create-only: signed `If-None-Match: *`, key absent | `200` | — |
| I2 / I4 | the same URL replayed (same or different bytes), key now exists | **`412 PreconditionFailed`**; the object is unchanged (I6) | A presigned URL is **create-only**. A leaked or replayed URL cannot overwrite a committed object. |
| I5 | the same URL with the signed `If-None-Match` header left out | `400 AccessDenied` | The precondition cannot be dropped. |

`probe2.py` and `probe3.py` (E9, M1–M6, I1–I6) ran on the same day against the same release.

Reproduce: start the pinned MinIO on `127.0.0.1:19000` with `probe` / `probeprobe`, then run
`python probe.py` and `python quota.py` (boto3). `quota.py` needs `mc quota set <alias>/quota --size 1MiB` first.
