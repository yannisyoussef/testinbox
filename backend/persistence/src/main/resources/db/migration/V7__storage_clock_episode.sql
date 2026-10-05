-- ADR-035 §5 (TI-STORAGE-003c): a durable record of an out-of-bound DB↔storage
-- clock-offset episode.
--
-- An observer (API cleaner or ingestion node) writes this one row FIRST, in
-- its own tiny transaction that touches no reservation row, and only then
-- holds the reservations. Every cleaner applies a recorded episode before it
-- releases anything, and deletes the row in the same transaction as the hold.
-- So a process that dies after observing, but before its hold committed,
-- leaves this row behind, and the next process applies the hold before any
-- affected reservation can be released.
--
-- Expand-only: a new table. Artifacts that predate it never read it.
CREATE TABLE storage_clock_episode (
    id               smallint    PRIMARY KEY CHECK (id = 1),
    -- The largest |offset| + error observed. Only ever raised.
    max_offset_ms    bigint      NOT NULL CHECK (max_offset_ms >= 0),
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL
);
