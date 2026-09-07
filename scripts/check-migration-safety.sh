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
# Exit codes:  0 = all migrations expand-only
#              1 = a rollback-breaking construct with NO declaration
#              2 = usage error
#              3 = only declared-unsafe migrations (needs release handling)
set -uo pipefail

MIGRATIONS_DIR="${1:-backend/persistence/src/main/resources/db/migration}"
DECLARATION_MARKER="testinbox:rollback-unsafe"

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
  echo "usage: $(basename "$0") [migrations-directory]" >&2
  echo "no such directory: $MIGRATIONS_DIR" >&2
  exit 2
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
    *"DROP COLUMN"*)
      echo "DROP COLUMN — the previous artifact still selects this column" ;;
    *"RENAME TO"*|*"RENAME COLUMN"*)
      echo "RENAME — the previous artifact refers to the old name" ;;
    *"ALTER COLUMN"*" TYPE "*)
      echo "ALTER COLUMN ... TYPE — a narrowed type breaks the previous artifact's reads and writes" ;;
    *"SET NOT NULL"*)
      echo "SET NOT NULL — the previous artifact's inserts may omit this column" ;;
  esac

  # NOT NULL without a default only breaks rollback when a column is ADDED:
  # the previous artifact's INSERT does not mention the new column, so every
  # write fails. A NOT NULL column inside CREATE TABLE is fine — nothing older
  # writes to a table that did not exist.
  case "$upper" in
    *"ADD COLUMN"*"NOT NULL"*)
      case "$upper" in
        *DEFAULT*) ;;
        *) echo "ADD COLUMN ... NOT NULL with no DEFAULT — the previous artifact's inserts omit it" ;;
      esac ;;
  esac
}

blocked=0
declared=0
scanned=0

shopt -s nullglob
for file in "$MIGRATIONS_DIR"/*.sql; do
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
shopt -u nullglob

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
