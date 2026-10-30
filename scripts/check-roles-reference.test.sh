#!/usr/bin/env bash
# Proves check-roles-reference.sh discriminates: the committed reference passes,
# and each MD1-shaped mutation of a copy of it fails. A guard that cannot fail
# is not a guard (docs/quality/strategy.md).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-roles-reference.sh"
REFERENCE="$SCRIPT_DIR/../deploy/database/roles.reference.sql"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

check() {
    local name="$1" expected="$2" file="$3" must="${4:-}"
    local output status
    output="$("$GATE" "$file" 2>&1)"
    status=$?
    if [ "$status" != "$expected" ]; then
        echo "FAIL — $name (expected exit $expected, got $status)"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
        return
    fi
    if [ -n "$must" ] && ! printf '%s\n' "$output" | grep -qE -- "$must"; then
        echo "FAIL — $name (output does not match /$must/)"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
        return
    fi
    echo "ok   — $name"
    pass=$((pass + 1))
}

# mutate <name> <python-expression over s> → $TMP/<name>.sql, a mutated copy.
mutate() {
    local name="$1" expression="$2"
    python3 - "$REFERENCE" "$TMP/$name.sql" "$expression" <<'PY'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
out = eval(sys.argv[3], {"s": s})
assert out != s, "mutation did not change the file"
open(sys.argv[2], "w", encoding="utf-8").write(out)
PY
}

BEGIN_LINE='s.replace("\nBEGIN;\n", "\n{}\nBEGIN;\n", 1)'

check "the committed reference passes" 0 "$REFERENCE" 'keeps the reviewed shape'

mutate pre-begin-ti-set "${BEGIN_LINE/\{\}/SET ti.ing_allow = 'inbox';}"
check "a pre-BEGIN SET ti.* fails (MD1: rewrites the contract's inputs)" 1 "$TMP/pre-begin-ti-set.sql" 'ti\.\* setting name'

mutate set-config "${BEGIN_LINE/\{\}/SELECT set_config('testinbox.api_role', 'x', false);}"
check "set_config fails, even on a testinbox.* name" 1 "$TMP/set-config.sql" 'set_config'

mutate search-path "${BEGIN_LINE/\{\}/SET search_path = evil, pg_catalog;}"
check "a search_path change fails (MD1: catalog shadowing)" 1 "$TMP/search-path.sql" 'search_path'

mutate alter-role-search-path "s.replace('COMMIT;', 'ALTER ROLE CURRENT_USER SET search_path = evil;\nCOMMIT;', 1)"
check "ALTER ROLE ... SET search_path fails" 1 "$TMP/alter-role-search-path.sql" 'search_path'

mutate alter-role-set "s.replace('COMMIT;', 'ALTER ROLE CURRENT_USER SET work_mem = 1;\nCOMMIT;', 1)"
check "ALTER ROLE ... SET of any setting fails" 1 "$TMP/alter-role-set.sql" 'SET outside the three reviewed input lines'

mutate second-do "${BEGIN_LINE/\{\}/DO \$x\$ BEGIN NULL; END \$x\$;}"
check "a second DO block fails" 1 "$TMP/second-do.sql" 'exactly one DO \$guard\$ block, found 2'

mutate renamed-do "s.replace('\$guard\$', '\$g\$')"
check "a DO block under another tag fails" 1 "$TMP/renamed-do.sql" 'exactly one DO \$guard\$ block'

mutate do-executes "s.replace('END\n\$guard\$;', '    EXECUTE format(%L, 1);\nEND\n\$guard\$;', 1)"
check "EXECUTE inside the DO block fails (dynamic SQL)" 1 "$TMP/do-executes.sql" 'EXECUTE inside the DO \$guard\$ block'

mutate do-perform "s.replace('END\n\$guard\$;', '    PERFORM pg_sleep(0);\nEND\n\$guard\$;', 1)"
check "PERFORM inside the DO block fails" 1 "$TMP/do-perform.sql" 'PERFORM inside the DO \$guard\$ block'

mutate do-sets "s.replace('END\n\$guard\$;', '    SET LOCAL statement_timeout = 0;\nEND\n\$guard\$;', 1)"
check "SET inside the DO block fails" 1 "$TMP/do-sets.sql" 'SET inside the DO \$guard\$ block'

mutate set-local "${BEGIN_LINE/\{\}/SET LOCAL lock_timeout = 0;}"
check "any other SET statement fails" 1 "$TMP/set-local.sql" 'SET outside the three reviewed input lines'

mutate reset-all "${BEGIN_LINE/\{\}/RESET ALL;}"
check "RESET fails" 1 "$TMP/reset-all.sql" 'RESET outside the three reviewed input lines'

mutate changed-input "s.replace(\"SET testinbox.api_role = :'api_role';\", \"SET testinbox.api_role = :'ingestion_role';\", 1)"
check "an input line bound to the wrong variable fails" 1 "$TMP/changed-input.sql" 'outside the three reviewed input lines'

mutate include "${BEGIN_LINE/\{\}/\\\\i extra.sql}"
check "a psql include fails (text the guard never saw)" 1 "$TMP/include.sql" 'psql meta-command'

mutate gexec "${BEGIN_LINE/\{\}/SELECT 1 \\\\gexec}"
check "a trailing \\gexec fails (it runs text the guard never saw)" 1 "$TMP/gexec.sql" 'psql meta-command after a statement'

mutate block-comment "${BEGIN_LINE/\{\}//* SET ti.ing_allow = 1; */}"
check "a block comment fails rather than hiding text" 1 "$TMP/block-comment.sql" 'block comment'

mutate comment-only "${BEGIN_LINE/\{\}/-- documentation may say set_config, search_path or SET ti.x}"
check "the same words inside a -- comment are not findings" 0 "$TMP/comment-only.sql" 'keeps the reviewed shape'

COMMIT_LINE="s.replace('\\nCOMMIT;', '\\n{}\\nCOMMIT;', 1)"
mutate owner-to "${COMMIT_LINE/\{\}/ALTER TABLE human_account OWNER TO :\"api_role\";}"
check "ALTER TABLE ... OWNER TO fails (ownership is operator authority)" 1 "$TMP/owner-to.sql" 'OWNER TO'

mutate alter-group "${COMMIT_LINE/\{\}/ALTER GROUP testinbox_owner ADD USER api;}"
check "ALTER GROUP ... ADD USER fails (membership without GRANT)" 1 "$TMP/alter-group.sql" 'role definition or membership'

mutate create-role "${COMMIT_LINE/\{\}/CREATE ROLE sneaky IN ROLE testinbox_owner;}"
check "CREATE ROLE ... IN ROLE fails" 1 "$TMP/create-role.sql" 'role definition or membership'

mutate grant-option "${COMMIT_LINE/\{\}/GRANT SELECT ON inbox TO :\"api_role\" WITH GRANT OPTION;}"
check "WITH GRANT OPTION fails" 1 "$TMP/grant-option.sql" 'GRANT\/ADMIN OPTION'

mutate grant-trigger "${COMMIT_LINE/\{\}/GRANT TRIGGER, TRUNCATE ON human_account TO :\"api_role\";}"
check "GRANT TRIGGER/TRUNCATE fails" 1 "$TMP/grant-trigger.sql" 'TRIGGER\/TRUNCATE'

mutate grant-tablespace "${COMMIT_LINE/\{\}/GRANT CREATE ON TABLESPACE pg_default TO :\"api_role\";}"
check "GRANT ON TABLESPACE fails" 1 "$TMP/grant-tablespace.sql" 'object class'

mutate schema-create "${COMMIT_LINE/\{\}/GRANT CREATE ON SCHEMA public TO :\"ingestion_role\";}"
check "GRANT CREATE ON SCHEMA fails (only USAGE on a schema is reviewed)" 1 "$TMP/schema-create.sql" 'only USAGE on a schema'

mutate schema-all "${COMMIT_LINE/\{\}/GRANT USAGE, CREATE ON SCHEMA public TO :\"api_role\";}"
check "GRANT USAGE, CREATE ON SCHEMA fails" 1 "$TMP/schema-all.sql" 'only USAGE on a schema'

mutate database-temp "${COMMIT_LINE/\{\}/GRANT TEMPORARY ON DATABASE testinbox TO :\"ingestion_role\";}"
check "GRANT TEMPORARY ON DATABASE fails" 1 "$TMP/database-temp.sql" 'GRANT ON DATABASE'

mutate default-privileges "${COMMIT_LINE/\{\}/ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :\"ingestion_role\";}"
check "ALTER DEFAULT PRIVILEGES fails" 1 "$TMP/default-privileges.sql" 'ALTER DEFAULT PRIVILEGES'

mutate membership "${COMMIT_LINE/\{\}/GRANT pg_read_all_data TO :\"ingestion_role\";}"
check "a role-membership GRANT fails" 1 "$TMP/membership.sql" 'role-membership GRANT'

mutate owner-membership "${COMMIT_LINE/\{\}/GRANT CURRENT_USER TO :\"api_role\";}"
check "granting the owner's membership fails" 1 "$TMP/owner-membership.sql" 'role-membership GRANT'

mutate do-assign "s.replace('END\n\$guard\$;', \"    r := 'x';\nEND\n\$guard\$;\", 1)"
check "an assignment inside the DO block fails" 1 "$TMP/do-assign.sql" 'assignment \(:=\)'

mutate do-initializer "s.replace(\"api       text := current_setting('testinbox.api_role');\", \"api       text := lower(current_setting('testinbox.api_role'));\", 1)"
check "a DECLARE initializer other than current_setting fails" 1 "$TMP/do-initializer.sql" 'an initializer inside DO'

mutate do-call "s.replace('END\n\$guard\$;', \"    IF format('%s', r) = '' THEN RAISE EXCEPTION 'x'; END IF;\nEND\n\$guard\$;\", 1)"
check "a function call other than pg_has_role/current_setting in the DO block fails" 1 "$TMP/do-call.sql" 'a call to format\(\)'

mutate do-qualified-call "s.replace('END\n\$guard\$;', '    IF public.evil() THEN RAISE EXCEPTION %L; END IF;\nEND\n\$guard\$;', 1)"
check "a schema-qualified user function call in the DO block fails" 1 "$TMP/do-qualified-call.sql" 'a call to public.evil\(\)'

echo
echo "check-roles-reference: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
