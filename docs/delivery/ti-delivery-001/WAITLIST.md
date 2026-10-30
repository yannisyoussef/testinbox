# Explicit beta waitlist (M3g)

A verified, active human account may explicitly join or withdraw. A login,
verification, capacity refusal or page view does not enroll it. Joining never
creates a workspace, project, admission, API key or outgoing email. It does
not grant authority over product data. The account endpoint returns only its
own status and timestamps, never a position, capacity or another applicant.

`ManageBetaWaitlist` is the sole writer. Each operation locks the fixed cohort,
then rechecks the account, purges expired/admitted entries and performs its
single transition in one transaction. Independent processes share PostgreSQL
serialization. A unique account primary key and unique places 1–1000 enforce
the physical entry ceiling even if an application path is broken. A lower
configured ceiling counts every existing entry, including places above its
new range. It refuses new entries rather than evicting existing applicants.
Retries preserve joined/expiry timestamps. Explicit withdrawal frees a place;
participant admission places remain consumed under ADR-036.

Configuration on the API only:

```
TESTINBOX_ACCOUNTS_WAITLIST_RETENTION=30d
TESTINBOX_ACCOUNTS_WAITLIST_MAXIMUM=1000
```

Thirty days is a proposed implementation default, pending the consolidated
owner retention decision before release. Configure 1–90 days and 1–1000
entries. An out-of-range policy refuses new enrollment while still allowing
existing entries to be read or withdrawn. Changing retention cannot silently
extend an existing entry. A minute scheduler removes expired/admitted entries
without requiring another signup; its closed-outcome failure metric remains
visible. Account deletion cascades the entry. Account/identity retention and
participant deletion/recovery remain separate unfinished release work.

V16 is expand-only. Waitlist rows are excluded from off-host backups and the
checker refuses reclassifying them as durable. This preserves withdrawal
through restore and avoids restoring old enrollment consent. Normal restarts
preserve the database rows; disaster recovery can lose the waitlist, so an
applicant may need to sign in and explicitly rejoin. Do not promise recovery
or send a campaign based on a stale export. The ten-minute identity protocols,
verified-account inventory and the thirty participant seats have their own
independent bounds; a waitlist place is none of those.

`/account/waitlist` GET/POST/DELETE uses the authenticated account, exact public
Origin for mutations and its session-bound CSRF token. No body/query can
select an email/account/workspace. All responses inherit no-store. Cookie
credentials and API keys do not authorize one another's endpoints.

Local verification: eleven real PostgreSQL cases include last-place concurrency,
a full thousand entries, SQL refusal of place 1001, withdrawal, expiry, admission,
rollback, ineligibility and shrinking the configured ceiling. That last case
failed before the count fix. Three real HTTP journeys verify session isolation,
CSRF, exact Origin, idempotent timestamps and private withdrawal. Two domain
and two architecture tests enforce lifetime/places and the sole writer, with
a concrete bypass fixture. Four maintenance/policy tests and the real shipped
YAML/env binding test cover cleanup and configuration. The backup suite passes
181/181, including genuine waitlist-containing dump refusal. Full backend and
result verifier: 1,648 tests, zero failures/errors/skips; Detekt and formatting
pass. This is local implementation evidence, not production enrollment approval.
