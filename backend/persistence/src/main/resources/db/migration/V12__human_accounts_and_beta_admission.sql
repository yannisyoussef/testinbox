-- ADR-036 / TI-DELIVERY-001. Expand-only foundation; no existing row changes.
-- No login/provider or admission endpoint is enabled by this migration.
-- Contact identity is control-plane personal data, never a mail-content row.
CREATE TABLE human_account (
    id                  uuid PRIMARY KEY,
    contact_email       text NOT NULL UNIQUE,
    contact_verified_at timestamptz,
    state               text NOT NULL DEFAULT 'ACTIVE'
                        CHECK (state IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING')),
    created_at          timestamptz NOT NULL,
    CONSTRAINT ck_human_account_contact CHECK (
        length(contact_email) BETWEEN 3 AND 254 AND contact_email = btrim(contact_email)
    )
);

-- This release has exactly one cohort. Another id or a changed number cannot
-- silently turn thirty approved places into a second public cohort.
CREATE TABLE beta_cohort (
    id              text PRIMARY KEY CHECK (id = 'beta-30'),
    admissions_open boolean NOT NULL DEFAULT false,
    capacity_ready  boolean NOT NULL DEFAULT false
);
INSERT INTO beta_cohort (id) VALUES ('beta-30');

-- Required for the composite FK below; the existing project PK is unchanged.
CREATE UNIQUE INDEX ux_project_id_workspace ON project (id, workspace_id);

CREATE TABLE beta_admission (
    cohort_id    text NOT NULL REFERENCES beta_cohort (id) CHECK (cohort_id = 'beta-30'),
    place        integer NOT NULL CHECK (place BETWEEN 1 AND 30),
    account_id   uuid UNIQUE REFERENCES human_account (id) ON DELETE SET NULL,
    workspace_id uuid UNIQUE REFERENCES workspace (id),
    project_id   uuid UNIQUE,
    admitted_at  timestamptz NOT NULL,
    PRIMARY KEY (cohort_id, place),
    FOREIGN KEY (project_id, workspace_id) REFERENCES project (id, workspace_id),
    CONSTRAINT ck_beta_admission_membership CHECK (
        (workspace_id IS NULL) = (project_id IS NULL)
    )
);

-- Account teardown clears workspace/project references after deleting its
-- content, but preserves the non-identifying consumed place. Neither account
-- deletion nor suspension automatically recycles an admission.
