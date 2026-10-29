-- ADR-036. Transient bounded request metadata, excluded from off-host backups.
CREATE TABLE human_request_global (
    id boolean PRIMARY KEY CHECK (id),
    window_start timestamptz NOT NULL,
    requests integer NOT NULL CHECK (requests BETWEEN 0 AND 3600),
    last_allowed boolean NOT NULL
);
INSERT INTO human_request_global VALUES (true, 'epoch', 0, false);
CREATE TABLE human_request_budget (
    account_id uuid PRIMARY KEY REFERENCES human_account (id) ON DELETE CASCADE,
    window_start timestamptz NOT NULL,
    requests integer NOT NULL CHECK (requests BETWEEN 0 AND 60),
    last_allowed boolean NOT NULL
);
