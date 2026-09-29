DROP TABLE IF EXISTS msg, resv, ws_acct, g_acct CASCADE;
CREATE TABLE ws_acct(workspace_id int PRIMARY KEY, committed bigint NOT NULL DEFAULT 0, reserved bigint NOT NULL DEFAULT 0);
CREATE TABLE g_acct(id int PRIMARY KEY, committed bigint NOT NULL DEFAULT 0, reserved bigint NOT NULL DEFAULT 0);
INSERT INTO g_acct VALUES (1,0,0);
INSERT INTO ws_acct(workspace_id) SELECT g FROM generate_series(1,26) g;
CREATE TABLE resv(id bigserial PRIMARY KEY, workspace_id int NOT NULL, bytes bigint NOT NULL,
  state text NOT NULL DEFAULT 'RESERVED', deadline_at timestamptz NOT NULL);
CREATE INDEX ix_resv_ws ON resv(workspace_id) INCLUDE (bytes);
CREATE TABLE msg(id bigserial PRIMARY KEY, workspace_id int NOT NULL, bytes bigint NOT NULL);
CREATE INDEX ix_msg_ws ON msg(workspace_id, id);
-- mode A (derived global): trigger maintains the workspace row only
CREATE OR REPLACE FUNCTION acct_ws() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN UPDATE ws_acct SET committed=committed+NEW.bytes WHERE workspace_id=NEW.workspace_id;
  ELSE UPDATE ws_acct SET committed=committed-OLD.bytes WHERE workspace_id=OLD.workspace_id; END IF;
  RETURN NULL; END $$;
-- mode B (hot global row): trigger maintains workspace AND global rows
CREATE OR REPLACE FUNCTION acct_ws_g() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    UPDATE g_acct SET committed=committed+NEW.bytes WHERE id=1;
    UPDATE ws_acct SET committed=committed+NEW.bytes WHERE workspace_id=NEW.workspace_id;
  ELSE
    UPDATE g_acct SET committed=committed-OLD.bytes WHERE id=1;
    UPDATE ws_acct SET committed=committed-OLD.bytes WHERE workspace_id=OLD.workspace_id; END IF;
  RETURN NULL; END $$;
