#!/usr/bin/env bash
# Guards the artifact-rollback promise (ADR-028/029, docs/dev/rollback.md).
#
# THE PROMISE: a previous artifact must be able to run against a newer schema,
# because that is the entire rollback story — roll the images back, leave the
# database alone. It holds only while migrations are expand-only. Until now it
# was held up by prose in a document and a reviewer noticing.
#
# WHAT THIS IS NOT: a proof that a migration is safe. SQL cannot be statically
# proven compatible, and pretending otherwise would be worse than nothing —
# a gate that looks authoritative and is not. This detects the constructs that
# are *unambiguously* rollback-breaking, and leaves the judgement calls to
# review (see .github/CODEOWNERS).
#
# Specifically it does NOT catch constraint TIGHTENING — dropping a constraint
# and adding a narrower one reads, statement by statement, exactly like the
# widening that ADR-026 legitimately did in V2. That gap is real, is documented
# in docs/dev/rollback.md, and is why migrations have a code owner.
#
# DECLARING AN INTENTIONAL BREAK: put this marker in the migration file
#
#     -- testinbox:rollback-unsafe: <why, and the release plan>
#
# which changes the outcome from "blocked" to "declared" (exit 3). A declared
# migration is not waved through: docs/dev/release-process.md requires it to be
# handled explicitly before it can reach master.
#
# SCOPE. By default every migration in the directory is scanned, which is what
# a pull request wants: the whole tree must remain expand-only. `--since <ref>`
# narrows to migrations ADDED relative to that ref, which is what a release
# needs — migrations are immutable once applied, so scanning the whole history
# forever would mean one declared rollback-unsafe migration blocked every
# future release permanently, with no way to acknowledge it and move on.
#
# Exit codes:  0 = all migrations in scope are expand-only
#              1 = a rollback-breaking construct with NO declaration
#              2 = usage error
#              3 = only declared-unsafe migrations (needs release handling)
set -uo pipefail

MIGRATIONS_DIR="backend/persistence/src/main/resources/db/migration"
DECLARATION_MARKER="testinbox:rollback-unsafe"
SINCE_REF=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --since) SINCE_REF="${2:-}"; shift 2 ;;
    --since=*) SINCE_REF="${1#*=}"; shift ;;
    -*) echo "usage: $(basename "$0") [--since <git-ref>] [migrations-directory]" >&2; exit 2 ;;
    *) MIGRATIONS_DIR="$1"; shift ;;
  esac
done

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
  echo "usage: $(basename "$0") [--since <git-ref>] [migrations-directory]" >&2
  echo "no such directory: $MIGRATIONS_DIR" >&2
  exit 2
fi

# Files to scan: everything, or only what this range adds.
declare -a FILES=()
if [[ -n "$SINCE_REF" ]]; then
  while IFS= read -r file; do
    [[ -n "$file" && -f "$file" ]] && FILES+=("$file")
  done < <(git diff --name-only --diff-filter=AM "$SINCE_REF" -- "$MIGRATIONS_DIR" 2>/dev/null | grep '\.sql$' || true)
  echo "scope: migrations added or modified since $SINCE_REF"
  if (( ${#FILES[@]} == 0 )); then
    echo "no migrations added since $SINCE_REF — nothing to check"
    exit 0
  fi
else
  shopt -s nullglob
  FILES=("$MIGRATIONS_DIR"/*.sql)
  shopt -u nullglob
  # A blocking gate that silently scans nothing is worse than no gate: if the
  # migrations directory ever moves, this must fail rather than report success.
  # Checked here rather than after the loop because bash 3.2 (macOS) treats an
  # empty array expansion under `set -u` as an unbound variable.
  if (( ${#FILES[@]} == 0 )); then
    echo "no migrations found in $MIGRATIONS_DIR — the gate scanned nothing" >&2
    exit 2
  fi
fi

# Strips SQL comments (block and line), flattens to one line, and splits on
# statement boundaries. Comment stripping is what makes the gate
# false-positive-resistant: a migration whose *comment* explains why it is not
# dropping a table must not be flagged for containing the words.
normalize_statements() {
  awk '
    {
      line = $0
      out = ""
      i = 1
      while (i <= length(line)) {
        two = substr(line, i, 2)
        if (in_block) {
          if (two == "*/") { in_block = 0; i += 2 } else { i++ }
          continue
        }
        if (two == "/*") { in_block = 1; i += 2; continue }
        if (two == "--") { break }
        out = out substr(line, i, 1)
        i++
      }
      printf "%s ", out
    }
  ' "$1" | tr '\n' ' ' | tr ';' '\n'
}

# Each rule is "why it breaks rollback", not just a pattern.
check_statement() {
  local statement="$1"
  local upper
  upper=$(printf '%s' "$statement" | tr '[:lower:]' '[:upper:]' | tr -s ' ')

  case "$upper" in
    *"DROP TABLE"*)
      echo "DROP TABLE — the previous artifact still reads this table" ;;
    *"DROP SCHEMA"*)
      echo "DROP SCHEMA — takes every table with it" ;;
    *"DROP VIEW"*|*"DROP SEQUENCE"*|*"DROP TYPE"*)
      echo "DROP of a schema object the previous artifact may still reference" ;;
    *"TRUNCATE"*)
      echo "TRUNCATE — destroys rows the previous artifact expects to read" ;;
    *"RENAME TO"*|*"RENAME COLUMN"*)
      echo "RENAME — the previous artifact refers to the old name" ;;
    *"SET NOT NULL"*)
      echo "SET NOT NULL — the previous artifact's inserts may omit this column" ;;
  esac

  # `COLUMN` is OPTIONAL in PostgreSQL's ALTER TABLE grammar, so matching only
  # the long form would let `ALTER TABLE t DROP c` and
  # `ALTER TABLE t ALTER c TYPE integer` through — both valid, both
  # rollback-breaking. Match on ALTER TABLE plus the operation instead, and
  # exclude the DROPs that only ever loosen.
  case "$upper" in
    *"ALTER TABLE"*)
      case "$upper" in
        *"DROP CONSTRAINT"*|*"DROP DEFAULT"*|*"DROP NOT NULL"*) ;;
        *"DROP "*) echo "ALTER TABLE ... DROP — the previous artifact still selects this column" ;;
      esac
      case "$upper" in
        *" TYPE "*) echo "ALTER TABLE ... TYPE — a narrowed type breaks the previous artifact's reads and writes" ;;
      esac ;;
  esac

  # NOT NULL without a default only breaks rollback when a column is ADDED:
  # the previous artifact's INSERT does not mention the new column, so every
  # write fails. A NOT NULL column inside CREATE TABLE is fine — nothing older
  # writes to a table that did not exist.
  #
  # Checked per ADD-COLUMN CLAUSE, not per statement: one ALTER TABLE may carry
  # several, and a statement-wide DEFAULT search would let
  # `ADD COLUMN a text NOT NULL, ADD COLUMN b text DEFAULT 'x'` pass on b's
  # default while a has none.
  case "$upper" in
    *"ADD COLUMN"*"NOT NULL"*)
      local clause
      while IFS= read -r clause; do
        case "$clause" in
          *"NOT NULL"*)
            case "$clause" in
              *DEFAULT*) ;;
              *) echo "ADD COLUMN ... NOT NULL with no DEFAULT — the previous artifact's inserts omit it"; break ;;
            esac ;;
        esac
      done < <(printf '%s' "$upper" | tr ',' '\n' | grep "ADD COLUMN") ;;
  esac
}

blocked=0
declared=0
scanned=0

for file in "${FILES[@]}"; do
  scanned=$((scanned + 1))
  # Read the declaration from the RAW file: it lives in a comment, which
  # normalization deliberately removes.
  is_declared=0
  grep -qi -- "$DECLARATION_MARKER" "$file" && is_declared=1

  findings=""
  while IFS= read -r statement; do
    [[ -n "${statement// /}" ]] || continue
    finding=$(check_statement "$statement")
    [[ -n "$finding" ]] && findings+="    - $finding"$'\n'
  done < <(normalize_statements "$file")

  if [[ -z "$findings" ]]; then
    echo "OK:       $(basename "$file") — expand-only"
    if (( is_declared )); then
      echo "WARNING:  $(basename "$file") declares $DECLARATION_MARKER but contains nothing this gate considers unsafe"
    fi
  elif (( is_declared )); then
    echo "DECLARED: $(basename "$file") — rollback-breaking, and says so:"
    printf '%s' "$findings"
    declared=$((declared + 1))
  else
    echo "BLOCKED:  $(basename "$file") — rollback-breaking with no declaration:"
    printf '%s' "$findings"
    blocked=$((blocked + 1))
  fi
done

echo "----"
echo "scanned $scanned migration(s) in $MIGRATIONS_DIR"

if (( blocked > 0 )); then
  cat >&2 <<EOF
$blocked migration(s) would break artifact rollback.

Artifact rollback (ADR-028) works only while a previous artifact can run
against a newer schema. Either rewrite this expand-only — add, backfill, and
contract in a LATER release once nothing reads the old shape — or, if the
break is genuinely intended, declare it in the migration file:

    -- $DECLARATION_MARKER: <why, and the release plan>

A declaration does not make it safe. It makes it visible, and
docs/dev/release-process.md requires explicit handling before it reaches
master.
EOF
  exit 1
fi

if (( declared > 0 )); then
  echo "$declared migration(s) are declared rollback-unsafe: artifact rollback across this"
  echo "release is NOT available. See docs/dev/release-process.md before promoting to master."
  exit 3
fi
