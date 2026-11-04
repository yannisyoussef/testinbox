#!/usr/bin/env bash
# The backup gate must be able to fail, including on a backup that examines
# nothing — a green result on an empty dump is exactly the false comfort a
# restore drill exists to remove.
#
# Two kinds of case. Fixture dumps cover every lexical shape a plain-SQL dump
# can take (a data statement is only a top-level COPY/INSERT; function bodies,
# strings, comments and COPY data rows are opaque; anything unaccountable is
# refused). The second half dumps a GENUINE migrated database with the real
# pg_dump, in every documented form, with hostile content rows — the V6/V8/V10
# trigger functions carry `INSERT INTO` lines in their bodies and once refused
# every V10 dump. That half needs Docker and FAILS rather than skips without
# it: a gate suite that quietly skips is the failure it exists to prevent.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GATE="$SCRIPT_DIR/check-backup-scope.sh"
MIGRATIONS="$REPO_ROOT/backend/persistence/src/main/resources/db/migration"
SCOPE_FILE="$REPO_ROOT/deploy/backup/scope.txt"
pass=0; fail=0
WORK=$(mktemp -d)
PG_CONTAINER=""
cleanup() {
  [[ -n "$PG_CONTAINER" ]] && docker rm -f "$PG_CONTAINER" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

check() {
  local name="$1" expected="$2"; shift 2
  local status out
  out=$("$GATE" "$@" 2>&1); status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"; echo "$out" | sed 's/^/       /' | tail -4; fail=$((fail + 1))
  fi
}
# Exit status AND the reason: a refusal for the wrong reason is not a proof.
check_says() {
  local name="$1" expected="$2" needle="$3"; shift 3
  local status out
  out=$("$GATE" "$@" 2>&1); status=$?
  if [[ "$status" == "$expected" && "$out" == *"$needle"* ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected saying '$needle', got $status)"; echo "$out" | sed 's/^/       /' | tail -4; fail=$((fail + 1))
  fi
}

# --- the real schema is fully classified ------------------------------------
check "every table in the real migrations is classified" 0 --classification "$MIGRATIONS"
mkdir -p "$WORK/mig"; printf 'CREATE TABLE audit_event (id uuid);\n' > "$WORK/mig/V9__audit.sql"
check "a new, unclassified table fails the classification check" 1 --classification "$WORK/mig"
# The forms a real migration takes that a naive pattern lets drift in: quoted,
# UNLOGGED (the idiomatic choice for exactly the transient tables this scope
# denies), split across lines, and a digit suffix that merely shares a prefix
# with a classified table.
for form in 'CREATE TABLE "audit_event" (id uuid);' 'CREATE UNLOGGED TABLE audit_event (id uuid);' \
            $'create table\n  audit_event (id uuid);' 'CREATE TABLE rate_bucket2 (id uuid);' \
            'CREATE TABLE IF NOT EXISTS public.audit_event (id uuid);'; do
  printf '%s\n' "$form" > "$WORK/mig/V9__audit.sql"
  check "unclassified table in the form [${form//$'\n'/\\n}] is caught" 1 --classification "$WORK/mig"
done
printf -- '-- CREATE TABLE audit_event would be wrong here\nCREATE TABLE workspace (id uuid);\n' > "$WORK/mig/V9__audit.sql"
check "a table name inside a comment is not a table" 0 --classification "$WORK/mig"
mkdir -p "$WORK/empty"
check "an empty migrations directory fails rather than passing vacuously" 1 --classification "$WORK/empty"

# --- plain-SQL dumps ----------------------------------------------------------
control_plane_dump() {
  cat <<'SQL'
SELECT pg_catalog.set_config('search_path', '', false);
CREATE TABLE public.workspace (id uuid);
COPY public.workspace (id, name) FROM stdin;
\.
COPY public.project (id) FROM stdin;
\.
COPY public.api_key (id, key_hash) FROM stdin;
\.
COPY public.exact_address_reservation (id) FROM stdin;
\.
COPY public.flyway_schema_history (installed_rank) FROM stdin;
\.
COPY public.human_account (id, contact_email) FROM stdin;
\.
COPY public.human_identity (provider, issuer, subject, account_id) FROM stdin;
\.
COPY public.beta_cohort (id) FROM stdin;
\.
COPY public.beta_admission (cohort_id, place) FROM stdin;
\.
COPY public.storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) FROM stdin;
\.
COPY public.participant_workspace (workspace_id, cohort_id, place) FROM stdin;
\.
COPY public.account_deletion (account_id, stage) FROM stdin;
\.
CREATE TABLE public.message (id uuid);
CREATE TABLE public.inbox (id uuid);
SQL
}
control_plane_dump > "$WORK/good.sql"
check "a control-plane-only dump passes (content tables schema-only)" 0 "$WORK/good.sql"
for table in human_account human_identity beta_cohort beta_admission participant_workspace storage_legacy_share account_deletion; do
  awk -v omitted="$table" '
    $0 ~ "^COPY public\\." omitted " " { skipping=1; next }
    skipping && $0 == "\\." { skipping=0; next }
    !skipping { print }
  ' "$WORK/good.sql" > "$WORK/missing-$table.sql"
  check_says "a backup missing $table is refused" 1 "MISSING: $table" "$WORK/missing-$table.sql"
done

{ control_plane_dump; printf 'COPY public.message (id, raw_object_key) FROM stdin;\n\\.\n'; } > "$WORK/content.sql"
check "a dump carrying message rows is refused — it would retain expired mail" 1 "$WORK/content.sql"

{ control_plane_dump; printf 'INSERT INTO public.attachment VALUES (1);\n'; } > "$WORK/insert.sql"
check "INSERT-style content data is caught as well as COPY" 1 "$WORK/insert.sql"
{ control_plane_dump; printf '\tCOPY public.message (id) FROM stdin;\n\\.\n'; } > "$WORK/tab.sql"
check "a COPY of content behind a leading tab is caught" 1 "$WORK/tab.sql"
{ control_plane_dump; printf 'INSERT INTO\n    public.message VALUES (1);\n'; } > "$WORK/multiline.sql"
check "an INSERT split across lines is caught" 1 "$WORK/multiline.sql"
{ control_plane_dump; printf 'COPY "public"."message" (id) FROM stdin;\n\\.\n'; } > "$WORK/quoted.sql"
check "a quoted, schema-qualified COPY of content is caught" 1 "$WORK/quoted.sql"

{ control_plane_dump | grep -v api_key; } > "$WORK/nokeys.sql"
check "a dump missing api_key is refused — a restore would lose every credential" 1 "$WORK/nokeys.sql"

{ control_plane_dump; printf 'COPY public.mystery_table (x) FROM stdin;\n\\.\n'; } > "$WORK/unknown.sql"
check "an unclassified table in the dump is refused" 1 "$WORK/unknown.sql"

printf 'CREATE TABLE public.workspace (id uuid);\n' > "$WORK/schema-only.sql"
check "a schema-only dump examines nothing and fails (positive control)" 1 "$WORK/schema-only.sql"
: > "$WORK/empty.sql"
check "an empty file fails" 1 "$WORK/empty.sql"

# --- a data statement is a TOP-LEVEL statement; text inside is opaque -------
# The defect Ops found on the first V10 dump: `INSERT INTO` lines inside the
# dollar-quoted bodies of the ADR-035 trigger functions were read as retained
# rows. Function bodies, string literals, comments and COPY data rows retain
# nothing, whatever they say.
{ control_plane_dump; cat <<'SQL'
CREATE FUNCTION public.storage_begin_sweep(p_node text) RETURNS bigint
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    INSERT INTO public.storage_sweep_run (node_id, started_seq, started_at)
    VALUES (p_node, nextval('public.storage_debt_order_seq'), now())
    RETURNING id;
$$;
CREATE FUNCTION public.storage_node_lower_stamp() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER AS
$$
BEGIN
        INSERT INTO public.storage_containment_watermark (id, last_lower_seq) VALUES (1, nextval('public.storage_debt_order_seq'))
        ON CONFLICT (id) DO UPDATE SET last_lower_seq = excluded.last_lower_seq;
    INSERT INTO public.message VALUES (1);
    COPY public.inbox FROM stdin;
    RETURN NEW;
END;
$$;
SQL
} > "$WORK/function-bodies.sql"
check "INSERT INTO inside a \$\$ function body is not retained data (Ops' V10 finding)" 0 "$WORK/function-bodies.sql"

{ control_plane_dump; cat <<'SQL'
CREATE FUNCTION public.storage_compact_ledger(p_batch integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER AS
$fn$
BEGIN
    -- a $$ inside a tagged body is text, and so is this:
    INSERT INTO public.message (id) VALUES (p_batch);
    EXECUTE $$INSERT INTO public.inbox VALUES (1)$$;
    RETURN 0;
END;
$fn$;
SQL
} > "$WORK/tagged-body.sql"
check "a \$tag\$ body containing \$\$ and INSERT INTO content is opaque" 0 "$WORK/tagged-body.sql"

{ control_plane_dump; cat <<'SQL'
COMMENT ON TABLE public.workspace IS 'INSERT INTO public.message VALUES (1);
COPY public.inbox FROM stdin;';
COMMENT ON COLUMN public.workspace.name IS E'it''s \'quoted\'\nINSERT INTO public.attachment VALUES (1);';
SELECT pg_catalog.set_config('search_path', '', false);
SELECT pg_catalog.setval('public.storage_debt_order_seq', 42, true);
SQL
} > "$WORK/strings.sql"
check "INSERT/COPY text inside standard and E'' string literals is opaque" 0 "$WORK/strings.sql"

{ control_plane_dump; cat <<'SQL'
-- INSERT INTO public.message VALUES (1);
/* COPY public.inbox FROM stdin;
   INSERT INTO public.attachment VALUES (1); */
SQL
} > "$WORK/comments.sql"
check "INSERT/COPY text inside -- and /* */ comments is opaque" 0 "$WORK/comments.sql"

cat > "$WORK/data-rows.sql" <<'SQL'
SELECT pg_catalog.set_config('search_path', '', false);
COPY public.workspace (id, name) FROM stdin;
1	INSERT INTO public.message VALUES (1);
2	COPY public.inbox FROM stdin;
3	$$ \. $fn$ 'unbalanced
\.
COPY public.project (id) FROM stdin;
\.
COPY public.api_key (id, key_hash) FROM stdin;
\.
COPY public.exact_address_reservation (id) FROM stdin;
\.
COPY public.flyway_schema_history (installed_rank) FROM stdin;
\.
COPY public.human_account (id) FROM stdin;
\.
COPY public.human_identity (subject) FROM stdin;
\.
COPY public.beta_cohort (id) FROM stdin;
\.
COPY public.beta_admission (place) FROM stdin;
\.
COPY public.storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) FROM stdin;
\.
COPY public.participant_workspace (workspace_id, place) FROM stdin;
\.
COPY public.account_deletion (account_id) FROM stdin;
\.
SQL
check "COPY data rows that look like statements, quotes or terminators are data, not statements" 0 "$WORK/data-rows.sql"

{ printf '\\restrict 7Kq9xY2mN4pL8vB3cD6fG1hJ5kM0nP\n'; control_plane_dump; printf '\\unrestrict 7Kq9xY2mN4pL8vB3cD6fG1hJ5kM0nP\n'; } > "$WORK/restrict.sql"
check "psql meta-commands a current pg_dump emits (\\restrict/\\unrestrict) are not statements" 0 "$WORK/restrict.sql"

# --- and real data in a forbidden table is still refused, in every spelling --
{ control_plane_dump; printf 'INSERT INTO public.message VALUES\n\t(1, %s),\n\t(2, %s);\n' "'a'" "'b'"; } > "$WORK/rows-per-insert.sql"
check_says "a multi-row INSERT (--rows-per-insert) of content is refused" 1 "RETAINS CONTENT: message" "$WORK/rows-per-insert.sql"
{ control_plane_dump; printf 'Insert Into public.message (id) Values (1);\n'; } > "$WORK/mixed-case.sql"
check_says "a mixed-case INSERT of content is refused" 1 "RETAINS CONTENT: message" "$WORK/mixed-case.sql"
{ control_plane_dump; printf 'CREATE FUNCTION f() RETURNS void LANGUAGE sql AS $$ SELECT 1 $$; INSERT INTO public.message VALUES (1);\n'; } > "$WORK/after-body.sql"
check_says "an INSERT of content on the same line after a function body is refused" 1 "RETAINS CONTENT: message" "$WORK/after-body.sql"
{ control_plane_dump; printf "INSERT INTO public.workspace VALUES ('it''s; INSERT INTO nothing');\nINSERT INTO public.inbox VALUES ('x');\n"; } > "$WORK/after-string.sql"
check_says "an INSERT of content after a string literal with an escaped quote is refused" 1 "RETAINS CONTENT: inbox" "$WORK/after-string.sql"
{ control_plane_dump; printf "COMMENT ON TABLE public.workspace IS '\$\$';\nINSERT INTO public.message VALUES (1);\n"; } > "$WORK/dollar-in-string.sql"
check_says "\$\$ inside a string does not open a body that would hide content" 1 "RETAINS CONTENT: message" "$WORK/dollar-in-string.sql"
{ control_plane_dump; printf 'COPY ONLY public.message (id) FROM stdin;\n1\n\\.\n'; } > "$WORK/copy-only.sql"
check_says "COPY ONLY of content is refused" 1 "RETAINS CONTENT: message" "$WORK/copy-only.sql"
{ control_plane_dump; printf '\\copy public.message from stdin\n1\n\\.\n'; } > "$WORK/backslash-copy.sql"
check_says "a psql \\copy of content is refused" 1 "RETAINS CONTENT: message" "$WORK/backslash-copy.sql"
{ control_plane_dump; printf 'INSERT INTO public.message OVERRIDING SYSTEM VALUE VALUES (1);\n'; } > "$WORK/overriding.sql"
check_says "INSERT ... OVERRIDING SYSTEM VALUE of content is refused" 1 "RETAINS CONTENT: message" "$WORK/overriding.sql"
{ control_plane_dump; printf 'COPY public.message (id, body) FROM stdin;\n1\tINSERT INTO public.workspace VALUES (1);\n\\.\n'; } > "$WORK/content-with-rows.sql"
check_says "content rows whose text names a control-plane table are still content" 1 "RETAINS CONTENT: message" "$WORK/content-with-rows.sql"

# --- input the reader cannot account for never passes -----------------------
{ control_plane_dump; printf 'CREATE FUNCTION f() RETURNS void LANGUAGE sql AS $$\n    INSERT INTO public.message VALUES (1);\n'; } > "$WORK/unterminated-body.sql"
check_says "an unterminated \$\$ body is refused as ambiguous" 1 "AMBIGUOUS INPUT: an unterminated dollar-quoted body" "$WORK/unterminated-body.sql"
{ control_plane_dump | sed '$d'; printf 'COPY public.flyway_schema_history (installed_rank) FROM stdin;\n1\n'; } > "$WORK/no-terminator.sql"
check_says "a COPY data block without its terminator is refused as ambiguous" 1 "AMBIGUOUS INPUT: a COPY data block without" "$WORK/no-terminator.sql"
{ control_plane_dump; printf '\\.\n'; } > "$WORK/stray-terminator.sql"
check_says "a stray \\. outside any COPY block is refused as ambiguous" 1 "AMBIGUOUS INPUT: a COPY terminator outside" "$WORK/stray-terminator.sql"
{ control_plane_dump; printf "COMMENT ON TABLE public.workspace IS 'never closed;\n"; } > "$WORK/unterminated-string.sql"
check_says "an unterminated string literal is refused as ambiguous" 1 "AMBIGUOUS INPUT: an unterminated string" "$WORK/unterminated-string.sql"
{ control_plane_dump; printf '/* never closed\n'; } > "$WORK/unterminated-comment.sql"
check_says "an unterminated block comment is refused as ambiguous" 1 "AMBIGUOUS INPUT: an unterminated comment" "$WORK/unterminated-comment.sql"
{ control_plane_dump; printf 'INSERT INTO public.message VALUES (1)\n'; } > "$WORK/truncated.sql"
check_says "a truncated final statement is refused as ambiguous, not ignored" 1 "AMBIGUOUS INPUT: a trailing statement" "$WORK/truncated.sql"
{ control_plane_dump; printf 'INSERT INTO (SELECT 1) VALUES (1);\n'; } > "$WORK/unnameable.sql"
check_says "a data statement whose table cannot be named is refused as ambiguous" 1 "AMBIGUOUS INPUT" "$WORK/unnameable.sql"
{ control_plane_dump; printf 'COPY "my table" (id) FROM stdin;\n\\.\n'; } > "$WORK/odd-identifier.sql"
check_says "a quoted identifier outside the schema's alphabet is refused as ambiguous" 1 "AMBIGUOUS INPUT: a quoted identifier outside the schema alphabet" "$WORK/odd-identifier.sql"
# Statements pg_dump never emits but which would create or carry rows at
# restore time are refused as unaccounted for, never assumed row-free.
for stmt in 'CREATE TABLE public.message AS SELECT * FROM (VALUES (1), (2)) t(id);' \
            'SELECT * INTO public.message FROM (VALUES (1)) t(id);' \
            'MERGE INTO public.message m USING (VALUES (1)) s(id) ON false WHEN NOT MATCHED THEN INSERT VALUES (s.id);' \
            'WITH t AS (SELECT 1 AS id) INSERT INTO public.message SELECT id FROM t;' \
            'DO $$ BEGIN INSERT INTO public.message (id) VALUES (1); END $$;' \
            "SELECT public.storage_begin_sweep('node');" \
            "UPDATE public.workspace SET name = 'x';" \
            'FROBNICATE public.message;' \
            'CREATE UNLOGGED TABLE public.inbox AS TABLE public.message;' \
            'create table public.attachment (id uuid) as (select 1);'; do
  { control_plane_dump; printf '%s\n' "$stmt"; } > "$WORK/unaccounted.sql"
  check_says "[$stmt] is refused as unaccounted for" 1 "AMBIGUOUS INPUT" "$WORK/unaccounted.sql"
done
# Restore-time execution the adversarial review found: statements pg_dump
# never emits that would create rows when the dump is restored. Each must be
# refused as unaccounted for; none may be assumed row-free.
for stmt in 'CREATE TABLE public.message AS(SELECT 1 AS id);' \
            'CREATE TABLE public.message AS/**/SELECT 1 AS id;' \
            'CREATE GLOBAL TEMPORARY TABLE public.scratch AS SELECT 1 AS id;' \
            "CREATE MATERIALIZED VIEW public.leak AS SELECT 'body' AS t WITH DATA;" \
            'REFRESH MATERIALIZED VIEW public.leak;' \
            "CREATE SUBSCRIPTION s CONNECTION 'host=elsewhere dbname=x' PUBLICATION p;" \
            'CREATE RULE r AS ON INSERT TO public.workspace DO ALSO INSERT INTO public.message VALUES (new.id);' \
            "SELECT pg_catalog.setval('public.s', public.evil());" \
            "SELECT pg_catalog.set_config('search_path', public.evil()::text, false);" \
            "SELECT pg_catalog.set_config('search_path', 'public', false);" \
            'SET search_path = public, pg_catalog;' \
            'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT public.evil();' \
            'ALTER TABLE ONLY public.workspace ADD CONSTRAINT c CHECK (public.evil() > 0);' \
            'ALTER TABLE public.workspace ENABLE TRIGGER ALL;' \
            'ALTER DATABASE testinbox SET search_path = public;' \
            'INSERT INTO public.workspace (id) VALUES (public.evil());' \
            "COPY public.workspace (id) FROM PROGRAM 'cmd';" \
            "COPY public.workspace (id) FROM '/var/lib/postgresql/rows.csv';" \
            'CREATE AGGREGATE public.agg (int) (sfunc = public.evil, stype = int);' \
            'CREATE INDEX ix ON public.workspace USING btree (public.evil(id));' \
            'CREATE VIEW public.v AS SELECT public.evil() AS x;' \
            'CREATE TEMPORARY VIEW v AS SELECT 1 AS x;' \
            'CREATE RECURSIVE VIEW public.v (n) AS SELECT 1;'; do
  { control_plane_dump; printf '%s\n' "$stmt"; } > "$WORK/exec.sql"
  check_says "[$stmt] is refused as unaccounted for" 1 "AMBIGUOUS INPUT" "$WORK/exec.sql"
done
# A trigger defined before the data would fire on every restored row; pg_dump
# puts triggers after the data, so data after a trigger is refused.
{ printf 'SELECT pg_catalog.set_config(%s, %s, false);\nCREATE FUNCTION public.evil_tg() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO public.message VALUES (new.id); RETURN NEW; END $$;\nCREATE TRIGGER t BEFORE INSERT ON public.workspace FOR EACH ROW EXECUTE FUNCTION public.evil_tg();\n' "'search_path'" "''"; control_plane_dump; } > "$WORK/trigger-first.sql"
check_says "table data after a trigger definition is refused (not pg_dump's order)" 1 "AMBIGUOUS INPUT: table data after a trigger definition" "$WORK/trigger-first.sql"
{ printf 'SELECT pg_catalog.set_config(%s, %s, false);\nCREATE TRIGGER t BEFORE INSERT ON public.workspace FOR EACH ROW EXECUTE FUNCTION public.f();\nINSERT INTO public.workspace (id) VALUES (7);\n' "'search_path'" "''"; control_plane_dump; } > "$WORK/trigger-then-insert.sql"
check_says "an INSERT after a trigger definition is refused" 1 "AMBIGUOUS INPUT: table data after a trigger definition" "$WORK/trigger-then-insert.sql"
{ control_plane_dump; printf 'CREATE TRIGGER t BEFORE INSERT ON public.workspace FOR EACH ROW EXECUTE FUNCTION public.f();\n\\copy public.workspace from stdin\n7\n\\.\n'; } > "$WORK/trigger-then-copy.sql"
check_says "a \\copy after a trigger definition is refused" 1 "AMBIGUOUS INPUT: table data after a trigger definition" "$WORK/trigger-then-copy.sql"
{ control_plane_dump; printf 'CREATE TRIGGER t AFTER INSERT ON public.workspace FOR EACH ROW EXECUTE FUNCTION public.storage_footprint_trust_guard();\n'; } > "$WORK/trigger-last.sql"
check "a trigger defined after the data, as pg_dump orders it, is accounted for" 0 "$WORK/trigger-last.sql"
# The V22 lifecycle view, as pg_dump writes it: a plain view holds no rows and runs nothing at restore.
{ control_plane_dump; printf "CREATE VIEW public.workspace_lifecycle_eligibility WITH (security_barrier='true') AS\n SELECT p.workspace_id, (COALESCE(((h.state = 'ACTIVE'::text) AND (a.workspace_id = p.workspace_id)), false) AND (EXISTS ( SELECT 1 FROM public.lifecycle_restore_clearance))) AS active\n   FROM ((public.participant_workspace p\n     LEFT JOIN public.beta_admission a ON (((a.cohort_id = p.cohort_id) AND (a.place = p.place))))\n     LEFT JOIN public.human_account h ON ((h.id = a.account_id)));\n"; } > "$WORK/plain-view.sql"
check "a plain view in pg_dump's form is accounted for" 0 "$WORK/plain-view.sql"
# psql variables would rewrite a statement after the reader classified it.
{ control_plane_dump; printf "\\set q 'TABLE public.message AS SELECT 1 AS id'\nCREATE :q;\n"; } > "$WORK/psql-var.sql"
check_says "a psql \\set is refused: variable interpolation rewrites statements after they were read" 1 "AMBIGUOUS INPUT: a psql meta-command" "$WORK/psql-var.sql"
{ control_plane_dump; printf '\\copy public.workspace from program %s\n' "'cmd'"; } > "$WORK/copy-program.sql"
check_says "a \\copy from program is refused" 1 "AMBIGUOUS INPUT: a \\copy that does not read from stdin" "$WORK/copy-program.sql"
# Round three: the buffer, quoted identifiers, the search_path premise, \connect, JOIN ... ON.
cols=$(seq -f 'col_%04g' 0 69 | paste -sd, -); vals=$(seq 0 69 | paste -sd, -)
{ control_plane_dump; printf 'CREATE TABLE public.message (%s) AS SELECT %s;\n' "$cols" "$vals"; } > "$WORK/long-ctas.sql"
check_says "a CREATE TABLE ... AS whose column list is longer than a line reader buffer is refused" 1 "AMBIGUOUS INPUT" "$WORK/long-ctas.sql"
pads=$(seq -f 'ADD COLUMN pad_%04g int' 0 200 | paste -sd, -)
{ control_plane_dump; printf 'CREATE FUNCTION public.evil() RETURNS bigint LANGUAGE sql AS $$ INSERT INTO public.message VALUES (9) RETURNING id $$;\nALTER TABLE public.workspace %s, ADD COLUMN x bigint DEFAULT public.evil();\n' "$pads"; } > "$WORK/long-alter.sql"
check_says "a user-function call pushed deep into a long ALTER is still refused" 1 "AMBIGUOUS INPUT" "$WORK/long-alter.sql"
big=$(head -c 70000 /dev/zero | tr '\0' 'x')
{ control_plane_dump; printf 'COMMENT ON TABLE public.workspace IS %s;\n' "'$big'"; } > "$WORK/huge-literal.sql"
check "a very long string literal does not overflow the reader (literals are not buffered)" 0 "$WORK/huge-literal.sql"
{ control_plane_dump; printf 'ALTER TABLE public.workspace %s;\n' "$(seq -f 'ADD COLUMN pad_%05g int' 0 4000 | paste -sd, -)"; } > "$WORK/overflow.sql"
check_says "a statement longer than the reader accounts for is refused, not read in part" 1 "AMBIGUOUS INPUT: a statement longer than the reader accounts for" "$WORK/overflow.sql"
for stmt in 'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT "public".evil();' \
            'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT public."evil"();' \
            'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT "public"."evil"();' \
            'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT "my schema".evil();' \
            'INSERT INTO public.workspace SELECT 1 FROM public.workspace a JOIN public.workspace b ON public.evil() > 0;' \
            'INSERT INTO public.workspace (id) SELECT public.evil();' \
            'CREATE FUNCTION pg_catalog.evil() RETURNS bigint LANGUAGE sql AS $$ SELECT 1 $$;' \
            'ALTER FUNCTION public.evil() SET SCHEMA pg_catalog;'; do
  { control_plane_dump; printf '%s\n' "$stmt"; } > "$WORK/exec3.sql"
  check_says "[$stmt] is refused" 1 "AMBIGUOUS INPUT" "$WORK/exec3.sql"
done
# With the prologue read, an unqualified name resolves only in pg_catalog
# (which nothing in the dump may create in), so these two are inert: PostgreSQL
# refuses them at restore. They are accounted for, not assumed.
{ control_plane_dump; printf 'ALTER TABLE ONLY public.workspace ALTER COLUMN created_at SET DEFAULT now();\nINSERT INTO public.workspace VALUES (1, NULL);\n'; } > "$WORK/builtin-default.sql"
check "a built-in default and a literal row after the prologue are accounted for" 0 "$WORK/builtin-default.sql"
for stmt in "SELECT pg_catalog.set_config('application_name', '', false);" \
            "SET SESSION search_path = public;" \
            "SET search_path TO public;" \
            "BEGIN;" \
            "COMMIT;"; do
  { printf '%s\n' "$stmt"; control_plane_dump | grep -v set_config; } > "$WORK/prologue-forgery.sql"
  check_says "[$stmt] does not stand in for the prologue" 1 "AMBIGUOUS INPUT" "$WORK/prologue-forgery.sql"
done
{ printf 'SELECT pg_catalog.set_config(%s, %s, false);\n' "'SEARCH_PATH'" "''"; control_plane_dump | grep -v set_config; } > "$WORK/prologue-upper.sql"
check "the prologue with the setting name in upper case is the same setting" 0 "$WORK/prologue-upper.sql"
for stmt in 'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT public . evil();' \
            'CREATE FUNCTION pg_catalog . evil() RETURNS bigint LANGUAGE sql AS $$ SELECT 1 $$;' \
            'ALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT "public" . "evil"();'; do
  { control_plane_dump; printf '%s\n' "$stmt"; } > "$WORK/dot-space.sql"
  check_says "[$stmt] is refused (whitespace around the dot)" 1 "AMBIGUOUS INPUT" "$WORK/dot-space.sql"
done
{ control_plane_dump | grep -v set_config; } > "$WORK/no-prologue.sql"
{ printf 'SELECT pg_catalog.set_config(%s, %s, true);\n' "'search_path'" "''"; control_plane_dump | grep -v set_config; } > "$WORK/local-prologue.sql"
check_says "a transaction-local (is_local = true) search_path does not count as the prologue" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/local-prologue.sql"
{ control_plane_dump; printf 'RESET search_path;\nALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT evil();\n'; } > "$WORK/reset.sql"
check_says "RESET search_path restores the default, so the prologue is required again" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/reset.sql"
{ control_plane_dump; printf 'RESET ALL;\nCOPY public.workspace (id) FROM stdin;\n\\.\n'; } > "$WORK/reset-all.sql"
check_says "RESET ALL likewise" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/reset-all.sql"
{ control_plane_dump; printf 'SET LOCAL search_path = %s;\n' "''"; } > "$WORK/set-local.sql"
check_says "SET LOCAL search_path is refused" 1 "AMBIGUOUS INPUT: a transaction-local setting" "$WORK/set-local.sql"
check_says "a dump that never empties search_path is refused before any DDL or data" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/no-prologue.sql"
{ printf 'CREATE FUNCTION evil(bigint) RETURNS boolean LANGUAGE sql AS $$ INSERT INTO public.message VALUES ($1) RETURNING true $$;\nCREATE TABLE public.workspace (id bigint CHECK (evil(id)));\n'; control_plane_dump; } > "$WORK/unqualified-check.sql"
check_says "a table with an unqualified CHECK function defined before the prologue is refused" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/unqualified-check.sql"
{ control_plane_dump; printf '\\connect "dbname=postgres user=postgres options=-csearch_path=public"\nALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT evil();\n'; } > "$WORK/connect-conninfo.sql"
check_says "a \\connect with a conninfo string is refused" 1 "AMBIGUOUS INPUT: a \\connect with more than a bare database name" "$WORK/connect-conninfo.sql"
{ control_plane_dump; printf '\\connect testinbox\nALTER TABLE public.workspace ADD COLUMN x bigint DEFAULT evil();\n'; } > "$WORK/connect-reset.sql"
check_says "after a bare \\connect the prologue is required again" 1 "AMBIGUOUS INPUT: DDL or data before the search_path was emptied" "$WORK/connect-reset.sql"
{ control_plane_dump; printf '\\connect testinbox\nSELECT pg_catalog.set_config(%s, %s, false);\nALTER TABLE ONLY public.workspace ADD CONSTRAINT ws_pkey PRIMARY KEY (id);\n' "'search_path'" "''"; } > "$WORK/connect-ok.sql"
check "a bare \\connect followed by the prologue again (pg_dump --create) is accounted for" 0 "$WORK/connect-ok.sql"
{ control_plane_dump; printf 'INSERT INTO public.workspace (id, name) OVERRIDING SYSTEM VALUE VALUES (1, %s), (2, NULL);\nINSERT INTO public.workspace DEFAULT VALUES;\n' "'a'"; } > "$WORK/insert-forms.sql"
check "the INSERT forms pg_dump --inserts emits are accounted for" 0 "$WORK/insert-forms.sql"
{ control_plane_dump; printf 'COMMENT ON FUNCTION public.storage_begin_sweep(p_node text) IS %s;\nGRANT EXECUTE ON FUNCTION public.f(bigint) TO app;\nCREATE INDEX ix ON public.workspace USING btree (id);\n' "'x'"; } > "$WORK/on-forms.sql"
check "ON before an object name in COMMENT, GRANT and CREATE INDEX is not a call" 0 "$WORK/on-forms.sql"
{ control_plane_dump; printf '\\i /var/backups/content.sql\n'; } > "$WORK/include.sql"
check_says "a psql \\i include is refused: it pulls in text the reader never sees" 1 "AMBIGUOUS INPUT: a psql meta-command" "$WORK/include.sql"
{ control_plane_dump; printf '\\! cat /etc/passwd\n'; } > "$WORK/shell.sql"
check_says "a psql shell escape is refused" 1 "AMBIGUOUS INPUT: a psql meta-command" "$WORK/shell.sql"
{ printf '\357\273\277SELECT pg_catalog.set_config(%s, %s, false);\nINSERT INTO public.message VALUES (1);\n' "'search_path'" "''"; control_plane_dump; } > "$WORK/bom.sql"
check_says "a UTF-8 byte-order mark does not hide the first statement" 1 "RETAINS CONTENT: message" "$WORK/bom.sql"
{ printf '\357\273\277'; control_plane_dump; } > "$WORK/bom-ok.sql"
check "a byte-order mark before a clean dump is tolerated" 0 "$WORK/bom-ok.sql"
{ control_plane_dump; cat <<'SQL'
CREATE TABLE public.audit (id uuid, total bigint GENERATED ALWAYS AS (id_len * 2) STORED, n integer DEFAULT CAST(1 AS integer));
SELECT pg_catalog.setval('public.storage_debt_order_seq', 7, true);
SELECT pg_catalog.set_config('search_path', '', false);
ALTER TABLE ONLY public.workspace ADD CONSTRAINT workspace_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.message ADD CONSTRAINT message_inbox_fk FOREIGN KEY (inbox_id) REFERENCES public.inbox(id) ON DELETE CASCADE;
REVOKE ALL ON FUNCTION public.storage_repair_ledger() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.storage_begin_sweep(p_node text) TO testinbox_app;
ALTER FUNCTION public.storage_compact_ledger(p_batch integer) OWNER TO testinbox;
COMMENT ON FUNCTION public.storage_begin_sweep(p_node text) IS 'gate F';
CREATE INDEX ix_inbox_address ON public.inbox USING btree (lower(address));
ALTER TABLE ONLY public.flyway_schema_history ALTER COLUMN installed_on SET DEFAULT now();
SQL
} > "$WORK/ddl-forms.sql"
check "generated columns, CAST defaults, setval/set_config and the usual DDL are accounted for" 0 "$WORK/ddl-forms.sql"
printf 'PGDMP\x01\x0e\x00\x04\x08binary archive bytes' > "$WORK/archive.dump"
check_says "a custom-format archive is refused with the pg_restore -l recipe" 1 "is a pg_dump archive, not plain SQL" "$WORK/archive.dump"

# --- pg_restore -l style table lists -----------------------------------------
printf 'workspace\nproject\napi_key\nexact_address_reservation\nflyway_schema_history\nhuman_account\nhuman_identity\nbeta_cohort\nbeta_admission\nparticipant_workspace\nstorage_legacy_share\naccount_deletion\n' > "$WORK/list.txt"
check "a table list of exactly the control plane passes" 0 "$WORK/list.txt"
# The documented recipe, run against a genuine `pg_restore -l` excerpt.
cat > "$WORK/restore.list" <<'LIST'
;
; Archive created at 2026-09-19 12:00:00 UTC
;
3117; 0 16391 TABLE DATA public workspace testinbox
3118; 0 16402 TABLE DATA public project testinbox
3119; 0 16413 TABLE DATA public api_key testinbox
3120; 0 16424 TABLE DATA public exact_address_reservation testinbox
3121; 0 16380 TABLE DATA public flyway_schema_history testinbox
3123; 0 16381 TABLE DATA public human_account testinbox
3126; 0 16384 TABLE DATA public human_identity testinbox
3124; 0 16382 TABLE DATA public beta_cohort testinbox
3125; 0 16383 TABLE DATA public beta_admission testinbox
3127; 0 16385 TABLE DATA public participant_workspace testinbox
3128; 0 16386 TABLE DATA public storage_legacy_share testinbox
3129; 0 16387 TABLE DATA public account_deletion testinbox
215; 1259 16391 TABLE public message testinbox
LIST
awk '/ TABLE DATA / {print $7}' "$WORK/restore.list" > "$WORK/from-restore.txt"
check "the documented pg_restore -l recipe yields a passing control-plane list" 0 "$WORK/from-restore.txt"
check_says "the raw pg_restore -l listing itself is refused, naming the recipe" 1 "looks like a raw 'pg_restore -l' listing" "$WORK/restore.list"
printf '3122; 0 16435 TABLE DATA public message testinbox\n' >> "$WORK/restore.list"
awk '/ TABLE DATA / {print $7}' "$WORK/restore.list" > "$WORK/from-restore-content.txt"
check "and refuses once message data appears in the archive listing" 1 "$WORK/from-restore-content.txt"
printf 'public.workspace\nPUBLIC.PROJECT\n"api_key"\nexact_address_reservation\nflyway_schema_history\nHUMAN_ACCOUNT\npublic.human_identity\npublic.beta_cohort\n"beta_admission"\npublic.participant_workspace\npublic.storage_legacy_share\n"public"."account_deletion"\n' > "$WORK/list-forms.txt"
check "schema prefixes, quoting and case in a list are normalised" 0 "$WORK/list-forms.txt"
{ cat "$WORK/list.txt"; printf 'inbox\n'; } > "$WORK/list-inbox.txt"
check_says "a list including inbox data is refused" 1 "RETAINS CONTENT: inbox" "$WORK/list-inbox.txt"
check "a missing input is a usage error" 2 "$WORK/nope.txt"

# The participant marker cannot be omitted or silently classified transient.
for mode in omit deny; do
  if [[ "$mode" == omit ]]; then sed '/^+ participant_workspace$/d' "$SCOPE_FILE" > "$WORK/marker-$mode.scope"
  else sed 's/^+ participant_workspace$/- participant_workspace/' "$SCOPE_FILE" > "$WORK/marker-$mode.scope"; fi
  out=$(BACKUP_SCOPE="$WORK/marker-$mode.scope" "$GATE" --classification "$MIGRATIONS" 2>&1); st=$?
  if [[ $st == 1 && "$out" == *"SCOPE MISCLASSIFIED: participant_workspace"* ]]; then
    echo "ok   — $mode of participant marker backup coverage is refused"; pass=$((pass + 1))
  else echo "FAIL — $mode of participant marker backup coverage passed ($st)"; fail=$((fail + 1)); fi
done

# --- ADR-035 (V6): the seven accounting/admission tables ----------------------
# Each must be classified, and classified '-'. Omitting one fails the
# classification check; marking one '+' is refused outright, in either mode,
# because backing it up would carry derived or transient state (ADR-035 §14).
for t in workspace_storage_account inbox_storage storage_delta storage_reservation \
         storage_ambiguity storage_node storage_admission_latch storage_cohort_plan workspace_deletion_charge storage_clock_episode human_session email_login_challenge human_auth_gate provider_login_state beta_waitlist human_request_global human_request_budget lifecycle_restore_clearance; do
  grep -qx -- "- $t" "$SCOPE_FILE" || { echo "FAIL — $t is not classified '-' in the real scope"; fail=$((fail + 1)); continue; }
  grep -vx -- "- $t" "$SCOPE_FILE" > "$WORK/omit-$t.txt"
  out=$(BACKUP_SCOPE="$WORK/omit-$t.txt" "$GATE" --classification "$MIGRATIONS" 2>&1); st=$?
  if [[ $st == 1 && "$out" == *"UNCLASSIFIED: $t"* ]]; then echo "ok   — omitting $t fails the classification"; pass=$((pass + 1))
  else echo "FAIL — omitting $t was not caught (exit $st)"; fail=$((fail + 1)); fi
  sed "s/^- $t\$/+ $t/" "$SCOPE_FILE" > "$WORK/keep-$t.txt"
  out=$(BACKUP_SCOPE="$WORK/keep-$t.txt" "$GATE" --classification "$MIGRATIONS" 2>&1); st=$?
  if [[ $st == 1 && "$out" == *"SCOPE MISCLASSIFIED: $t"* ]]; then echo "ok   — classifying $t '+' is refused"; pass=$((pass + 1))
  else echo "FAIL — classifying $t '+' was not refused (exit $st)"; fail=$((fail + 1)); fi
done
# The content tables share the same deny rule: marking one '+' is refused too.
for t in inbox message attachment; do
  sed "s/^- $t\$/+ $t/" "$SCOPE_FILE" > "$WORK/keep-$t.txt"
  out=$(BACKUP_SCOPE="$WORK/keep-$t.txt" "$GATE" --classification "$MIGRATIONS" 2>&1); st=$?
  if [[ $st == 1 && "$out" == *"SCOPE MISCLASSIFIED: $t"* ]]; then echo "ok   — classifying content table $t '+' is refused"; pass=$((pass + 1))
  else echo "FAIL — classifying content table $t '+' was not refused (exit $st)"; fail=$((fail + 1)); fi
done
{ control_plane_dump; printf 'COPY public.storage_delta (id) FROM stdin;\n\\.\n'; } > "$WORK/dump-ledger.sql"
check "a dump carrying the ADR-035 ledger's data is refused" 1 "$WORK/dump-ledger.sql"

# --- genuine pg_dump of a migrated database (needs Docker; fails without) ----
# The real migrations V1..V10 applied in order to a PostgreSQL 16 server (the
# same image the persistence and e2e suites use), Flyway's history table with
# one row per migration (Flyway's own DDL; the migrator is a Spring Boot app
# and is not what this suite proves), control-plane rows, and content rows
# whose text is hostile to a line-oriented reader. Then the real pg_dump, in
# every documented form.
echo "---- genuine pg_dump of a migrated database"
if ! docker info >/dev/null 2>&1; then
  echo "FAIL — Docker is not available: the genuine pg_dump regressions did not run (this suite fails rather than skips)"
  fail=$((fail + 1))
else
  PG_IMAGE="postgres:16-alpine"
  PG_CONTAINER="backup-scope-$$-$RANDOM"
  if ! docker run -d --name "$PG_CONTAINER" -e POSTGRES_PASSWORD=t -e POSTGRES_DB=testinbox "$PG_IMAGE" >/dev/null 2>&1; then
    echo "FAIL — could not start $PG_IMAGE"; fail=$((fail + 1))
  else
    # Over TCP, never the socket: the image's entrypoint runs a TEMPORARY
    # socket-only server while it initialises (before POSTGRES_DB exists), and
    # pg_isready/psql on the socket reach it and answer early — CI run
    # 39356010884 failed exactly so. Only the final server listens on
    # 127.0.0.1, and readiness is a real query against the named database,
    # not a handshake.
    PG_HOST=(-h 127.0.0.1 -U postgres -d testinbox)
    psql() { docker exec -i -e PGPASSWORD=t "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -q "${PG_HOST[@]}" "$@"; }
    migrate() { psql --single-transaction "$@"; }  # one transaction per migration, as Flyway runs them
    pg_dump() { docker exec -e PGPASSWORD=t "$PG_CONTAINER" pg_dump "${PG_HOST[@]}" "$@"; }
    ready=0
    for _ in $(seq 1 90); do
      if docker exec -e PGPASSWORD=t "$PG_CONTAINER" pg_isready -h 127.0.0.1 -U postgres -d testinbox >/dev/null 2>&1 &&
        psql -tAc "SELECT 1" </dev/null 2>/dev/null | grep -qx 1; then
        ready=1
        break
      fi
      sleep 1
    done
    (( ready )) || { echo "FAIL — $PG_IMAGE did not become ready over TCP within 90s"; fail=$((fail + 1)); }
    migrated=$ready
    # Flyway's history table (its DDL), then every migration in version order.
    (( ready )) && { psql <<'SQL' >/dev/null || migrated=0; }
CREATE TABLE "public"."flyway_schema_history" (
    "installed_rank" INT NOT NULL, "version" VARCHAR(50), "description" VARCHAR(200) NOT NULL,
    "type" VARCHAR(20) NOT NULL, "script" VARCHAR(1000) NOT NULL, "checksum" INTEGER,
    "installed_by" VARCHAR(100) NOT NULL, "installed_on" TIMESTAMP NOT NULL DEFAULT now(),
    "execution_time" INTEGER NOT NULL, "success" BOOLEAN NOT NULL,
    CONSTRAINT "flyway_schema_history_pk" PRIMARY KEY ("installed_rank"));
CREATE INDEX "flyway_schema_history_s_idx" ON "public"."flyway_schema_history" ("success");
SQL
    rank=0
    # Version order (V10 after V9, not after V1). An array, not a pipe: every
    # psql call below runs under `docker exec -i`, which would read the loop's
    # stdin as its own.
    migration_files=()
    while IFS= read -r f; do migration_files+=("$f"); done < <(ls "$MIGRATIONS"/V*.sql | sed -E 's#(.*/V([0-9]+)__.*)#\2 \1#' | sort -n | cut -d' ' -f2)
    for file in "${migration_files[@]}"; do
      version="${file##*/V}"; version="${version%%__*}"
      if ! migrate < "$file" >/dev/null 2>"$WORK/migrate.err"; then
        echo "FAIL — applying $(basename "$file") to the synthetic database failed: $(tail -2 "$WORK/migrate.err" | tr '\n' ' ')"
        fail=$((fail + 1)); migrated=0; break
      fi
      rank=$((rank + 1))
      psql -c "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
               VALUES ($rank, '$version', 'synthetic', 'SQL', '$(basename "$file")', 0, 'test', 1, true)" >/dev/null </dev/null || migrated=0
    done
    functions=$(psql -tAc "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname IN ('storage_begin_sweep', 'storage_compact_ledger', 'storage_node_lower_stamp')" 2>/dev/null </dev/null)
    if (( migrated )) && [[ "$functions" == "3" ]]; then
      echo "ok   — the real migrations V1..V$version applied to a PostgreSQL 16 database (V10 functions present)"; pass=$((pass + 1))
    else
      echo "FAIL — the synthetic database is not fully migrated (migrated=$migrated, V10 functions found: '$functions')"; fail=$((fail + 1)); migrated=0
    fi
    if (( migrated )); then
      # Control-plane rows, and content rows whose text would fool a line-oriented
      # reader: statements, terminators, quotes, backslashes, dollar quotes, newlines.
      psql <<'SQL' >/dev/null && seeded=1 || seeded=0
INSERT INTO workspace (id, name, created_at) VALUES ('11111111-1111-1111-1111-111111111111', E'ws ''quoted'' \\ backslash\nINSERT INTO public.message VALUES (1);', now());
INSERT INTO project (id, workspace_id, name, created_at) VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'p', now());
INSERT INTO human_account (id, contact_email, contact_verified_at, created_at) VALUES ('77777777-7777-7777-7777-777777777777', 'backup-fixture@example.invalid', now(), now());
INSERT INTO human_identity (provider, issuer, subject, account_id, created_at) VALUES ('EMAIL', 'testinbox', 'backup-fixture@example.invalid', '77777777-7777-7777-7777-777777777777', now());
INSERT INTO human_request_budget VALUES ('77777777-7777-7777-7777-777777777777', now(), 1, true);
INSERT INTO beta_waitlist (account_id,place,joined_at,expires_at) VALUES ('77777777-7777-7777-7777-777777777777', 1, now(), now()+interval '30 days');
INSERT INTO provider_login_state (state_hash,browser_hash,source_hash,provider,encrypted_request,created_at,expires_at) VALUES (repeat('d',64), repeat('e',64), repeat('f',64), 'GOOGLE', 'encrypted-fixture', now(), now()+interval '10 minutes');
INSERT INTO human_auth_gate (id) VALUES (1);
INSERT INTO email_login_challenge (id,email,browser_hash,source_hash,code_verifier,created_at,expires_at,attempts_remaining,state) VALUES ('88888888-8888-8888-8888-888888888888', 'backup-fixture@example.invalid', repeat('a',64), repeat('b',64), repeat('c',64), now(), now()+interval '10 minutes', 5, 'FAILED');
INSERT INTO human_session (verifier, account_id, created_at, expires_at, last_seen_at) VALUES (repeat('b', 64), '77777777-7777-7777-7777-777777777777', now(), now() + interval '1 hour', now());
INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) VALUES ('beta-30', 1, '77777777-7777-7777-7777-777777777777', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', now());
INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES ('11111111-1111-1111-1111-111111111111', 'beta-30', 1, now());
INSERT INTO storage_legacy_share VALUES ('88888888-8888-8888-8888-888888888888',134217728,268435456);
INSERT INTO account_deletion (account_id, stage, requested_at, stage_changed_at, erased_at) VALUES ('99999999-9999-9999-9999-999999999999', 'ERASED', now(), now(), now());
INSERT INTO api_key (id, workspace_id, project_id, key_hash, scopes, created_at, created_by_human_account_id) VALUES ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', repeat('a', 64), ARRAY['inboxes:write'], now(), '77777777-7777-7777-7777-777777777777');
INSERT INTO exact_address_reservation (id, workspace_id, local_part, inbox_id, status, reserved_at) VALUES ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'qa', '55555555-5555-5555-5555-555555555555', 'ACTIVE', now());
INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) VALUES ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'qa@inbox.testinbox.local', 'EXACT', 'ACTIVE', now(), now() + interval '1 hour');
INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key, raw_size_bytes, content_fingerprint, parse_status, subject, text_body)
VALUES ('66666666-6666-6666-6666-666666666666', '11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555', now(), 'smtp', 'qa@inbox.testinbox.local', '11111111-1111-1111-1111-111111111111/55555555-5555-5555-5555-555555555555/66666666-6666-6666-6666-666666666666/raw', 1234, repeat('f', 64), 'OK',
        E'\\.', E'COPY public.workspace (id) FROM stdin;\n\\.\nINSERT INTO public.workspace VALUES (1);\n$$ $fn$ ''unbalanced "quote\n-- not a comment\n/* not a comment');
SQL
      if (( seeded )); then
        echo "ok   — control-plane and hostile content rows seeded"; pass=$((pass + 1))
      else
        echo "FAIL — seeding the synthetic database failed"; fail=$((fail + 1))
      fi
      # Real authentication rows must be omitted, not just empty classified tables.
      for transient in human_session email_login_challenge human_auth_gate provider_login_state beta_waitlist human_request_global human_request_budget lifecycle_restore_clearance; do
        transient_args=(); for t in $(grep -E '^- ' "$SCOPE_FILE" | sed 's/^- //'); do
          [[ "$t" == "$transient" ]] || transient_args+=(--exclude-table-data="public.$t")
        done
        pg_dump "${transient_args[@]}" > "$WORK/auth-retained-$transient.sql"
        check_says "a genuine dump retaining $transient is refused" 1 "RETAINS CONTENT: $transient" "$WORK/auth-retained-$transient.sql"
      done
      # The dump forms docs/dev/production.md documents.
      exclude_args=(); for t in $(grep -E '^- ' "$SCOPE_FILE" | sed 's/^- //'); do exclude_args+=(--exclude-table-data="public.$t"); done
      keep_args=(); for t in $(grep -E '^\+ ' "$SCOPE_FILE" | sed 's/^+ //'); do keep_args+=(--table="public.$t"); done

      pg_dump "${exclude_args[@]}" > "$WORK/v10-exclude-table-data.sql" 2>"$WORK/dump.err" || echo "pg_dump failed: $(cat "$WORK/dump.err")"
      # Honest premise: this dump carries the function bodies Ops saw, with
      # their INSERT INTO lines, and no content rows.
      bodies=$(grep -cE '^[[:space:]]*INSERT INTO public\.(storage_sweep_run|storage_containment_watermark|storage_delta|storage_deletion_debt)' "$WORK/v10-exclude-table-data.sql")
      if (( bodies >= 3 )) && grep -q 'storage_begin_sweep' "$WORK/v10-exclude-table-data.sql"; then
        echo "ok   — the genuine V10 dump carries the trigger/function bodies with their INSERT INTO lines ($bodies)"; pass=$((pass + 1))
      else
        echo "FAIL — the genuine dump does not contain the function bodies the regression is about ($bodies lines)"; fail=$((fail + 1))
      fi
      check "genuine pg_dump --exclude-table-data for every '-' table passes (the Ops production form)" 0 "$WORK/v10-exclude-table-data.sql"
      pg_dump "${exclude_args[@]}" --exclude-table-data=public.participant_workspace > "$WORK/missing-participant-marker.sql"
      check_says "a genuine dump losing durable participant classification is refused" 1 "MISSING: participant_workspace" "$WORK/missing-participant-marker.sql"
      # Restore the full production-form dump into a fresh database. The
      # marker must survive with the same fixed share; authentication stays out.
      psql -c 'CREATE DATABASE marker_restore' </dev/null >/dev/null
      if docker exec -i -e PGPASSWORD=t "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -q -h 127.0.0.1 -U postgres -d marker_restore < "$WORK/v10-exclude-table-data.sql" > "$WORK/restore.log" 2>&1; then
        restored=$(docker exec -e PGPASSWORD=t "$PG_CONTAINER" psql -tAc "SELECT count(*) FROM participant_workspace WHERE place=1 AND payload_limit_bytes=134217728 AND footprint_limit_bytes=268435456" -h 127.0.0.1 -U postgres -d marker_restore)
        if [[ "$restored" == 1 ]]; then echo "ok   — genuine restore keeps the participant marker and fixed share"; pass=$((pass + 1))
        else echo "FAIL — restored marker/share mismatch"; fail=$((fail + 1)); fi
        # Account lifecycle (V22): the source carries a clearance row, the restore
        # must not, so the restored participant is inactive and no session exists.
        quarantine=$(docker exec -e PGPASSWORD=t "$PG_CONTAINER" psql -tAc "SELECT (SELECT count(*) FROM lifecycle_restore_clearance)::text || '/' || (SELECT count(*) FROM human_session)::text || '/' || (SELECT string_agg(active::text, ',') FROM workspace_lifecycle_eligibility)" -h 127.0.0.1 -U postgres -d marker_restore)
        source_clearance=$(psql -tAc "SELECT count(*) FROM lifecycle_restore_clearance" </dev/null)
        if [[ "$source_clearance" == 1 && "$quarantine" == "0/0/false" ]]; then
          echo "ok   — genuine restore is quarantined: no clearance, no session, the restored participant is inactive"; pass=$((pass + 1))
        else echo "FAIL — restore quarantine not in effect (source clearance $source_clearance, restored '$quarantine')"; fail=$((fail + 1)); fi
      else echo "FAIL — genuine backup restore failed: $(tail -2 "$WORK/restore.log")"; fail=$((fail + 1)); fi
      pg_dump "${keep_args[@]}" > "$WORK/v10-table.sql" 2>/dev/null
      check "genuine pg_dump --table for every '+' table passes" 0 "$WORK/v10-table.sql"
      pg_dump --inserts "${exclude_args[@]}" > "$WORK/v10-exclude-inserts.sql" 2>/dev/null
      check "genuine pg_dump --inserts with content excluded passes" 0 "$WORK/v10-exclude-inserts.sql"
      pg_dump --column-inserts --rows-per-insert=100 "${exclude_args[@]}" > "$WORK/v10-exclude-column-inserts.sql" 2>/dev/null
      check "genuine pg_dump --column-inserts --rows-per-insert with content excluded passes" 0 "$WORK/v10-exclude-column-inserts.sql"

      pg_dump > "$WORK/v10-full.sql" 2>/dev/null
      grep -q '^COPY public.message ' "$WORK/v10-full.sql" || { echo "FAIL — the full dump carries no message COPY block"; fail=$((fail + 1)); }
      check_says "a genuine full dump with real message rows is refused" 1 "RETAINS CONTENT: message" "$WORK/v10-full.sql"
      check_says "... and names the inbox rows too" 1 "RETAINS CONTENT: inbox" "$WORK/v10-full.sql"
      pg_dump --inserts > "$WORK/v10-full-inserts.sql" 2>/dev/null
      check_says "a genuine --inserts dump with real content rows (multiline, quoted, backslashed) is refused" 1 "RETAINS CONTENT: message" "$WORK/v10-full-inserts.sql"
      pg_dump --column-inserts --rows-per-insert=100 > "$WORK/v10-full-rows.sql" 2>/dev/null
      check_says "a genuine --column-inserts --rows-per-insert dump with content is refused" 1 "RETAINS CONTENT: message" "$WORK/v10-full-rows.sql"
      without() { local skip="$1" a; shift; for a in "$@"; do [[ "$a" == "--exclude-table-data=public.$skip" ]] || printf '%s\n' "$a"; done; }
      args=(); while IFS= read -r a; do args+=("$a"); done < <(without message "${exclude_args[@]}")
      pg_dump "${args[@]}" > "$WORK/v10-message-only.sql" 2>/dev/null
      check_says "a genuine dump that forgets to exclude only message is refused" 1 "RETAINS CONTENT: message" "$WORK/v10-message-only.sql"
      args=(); while IFS= read -r a; do args+=("$a"); done < <(without storage_delta "${exclude_args[@]}")
      pg_dump "${args[@]}" > "$WORK/v10-ledger.sql" 2>/dev/null
      check_says "a genuine dump that forgets to exclude the ADR-035 ledger is refused" 1 "RETAINS CONTENT: storage_delta" "$WORK/v10-ledger.sql"

      # Malformed versions of the genuine dump never pass.
      line=$(grep -n 'CREATE FUNCTION public.storage_begin_sweep' "$WORK/v10-exclude-table-data.sql" | cut -d: -f1)
      head -n "$((line + 3))" "$WORK/v10-exclude-table-data.sql" > "$WORK/v10-truncated-in-body.sql"
      check_says "the genuine dump truncated inside a function body is refused as ambiguous" 1 "AMBIGUOUS INPUT" "$WORK/v10-truncated-in-body.sql"
      awk 'BEGIN{done=0} /^COPY public\.workspace /{inblock=1} inblock && /^\\\.$/ && !done {done=1; inblock=0; next} {print}' "$WORK/v10-exclude-table-data.sql" > "$WORK/v10-missing-terminator.sql"
      check_says "the genuine dump with a COPY terminator removed is refused as ambiguous" 1 "AMBIGUOUS INPUT" "$WORK/v10-missing-terminator.sql"
      pg_dump --format=custom "${exclude_args[@]}" > "$WORK/v10.dump" 2>/dev/null
      check_says "the genuine custom-format archive is refused with the recipe" 1 "is a pg_dump archive" "$WORK/v10.dump"
      docker exec -i "$PG_CONTAINER" pg_restore -l < "$WORK/v10.dump" 2>/dev/null | awk '/ TABLE DATA / {print $7}' > "$WORK/v10-restore-list.txt"
      check "the documented pg_restore -l recipe on the genuine archive passes" 0 "$WORK/v10-restore-list.txt"
      pg_dump --format=custom > "$WORK/v10-full.dump" 2>/dev/null
      docker exec -i "$PG_CONTAINER" pg_restore -l < "$WORK/v10-full.dump" 2>/dev/null | awk '/ TABLE DATA / {print $7}' > "$WORK/v10-full-restore-list.txt"
      check_says "the recipe on a genuine full archive refuses the content entries" 1 "RETAINS CONTENT: message" "$WORK/v10-full-restore-list.txt"
    fi
  fi
fi

echo "----"
echo "check-backup-scope.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
