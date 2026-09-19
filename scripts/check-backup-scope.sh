#!/usr/bin/env bash
# Proves a database backup honours the data-lifecycle contract (ADR-034 §5):
# durable control-plane tables are present, content and transient tables carry
# NO data, and nothing unclassified slipped in.
#
# Ops owns the backup tooling; this repository owns what a backup may contain.
# This is the acceptance evidence for the "backups" production blocker: the
# real dump (or its table list) is run through it, and a backup that would
# quietly keep expired mail alive is refused.
#
# Input is either a plain-SQL pg_dump (COPY/INSERT statements mark tables that
# carry data) or a newline-separated list of tables that carry data, e.g.
#
#     pg_restore -l backup.dump | awk '/ TABLE DATA / {print $7}' > tables.txt
#
# (`pg_restore -l` lines read `3117; 0 16391 TABLE DATA public workspace owner`:
# field 6 is the schema, field 7 the table.)
#
# Usage:
#   check-backup-scope.sh <dump.sql | tables.txt>        validate a backup
#   check-backup-scope.sh --classification <migrations>  the scope file names
#                                                        every table in the schema
# Env: BACKUP_SCOPE overrides deploy/backup/scope.txt.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCOPE="${BACKUP_SCOPE:-$SCRIPT_DIR/../deploy/backup/scope.txt}"

fail() { echo "BACKUP SCOPE VIOLATION: $*" >&2; exit 1; }
usage() { echo "usage: $(basename "$0") <dump.sql|tables.txt> | --classification <migrations-dir>" >&2; exit 2; }

[[ -f "$SCOPE" ]] || { echo "scope file not found: $SCOPE" >&2; exit 2; }

declare -a KEEP=() DENY=()
while IFS= read -r line; do
  line="${line%%#*}"; line="${line// /}"
  [[ -n "$line" ]] || continue
  case "$line" in
    +*) KEEP+=("${line#+}") ;;
    -*) DENY+=("${line#-}") ;;
    *) echo "scope line '$line' must start with + or -" >&2; exit 2 ;;
  esac
done < "$SCOPE"
(( ${#KEEP[@]} > 0 && ${#DENY[@]} > 0 )) || { echo "scope must classify both kept and denied tables" >&2; exit 2; }

classified() {
  local t="$1" x
  for x in "${KEEP[@]}" "${DENY[@]}"; do [[ "$x" == "$t" ]] && return 0; done
  return 1
}

# --- the classification covers the whole schema -----------------------------
if [[ "${1:-}" == "--classification" ]]; then
  dir="${2:-}"; [[ -d "$dir" ]] || usage
  shopt -s nullglob
  files=("$dir"/*.sql)
  shopt -u nullglob
  (( ${#files[@]} > 0 )) || fail "no migrations found in $dir — the classification checked nothing"
  unclassified=0
  while IFS= read -r table; do
    [[ -n "$table" ]] || continue
    if ! classified "$table"; then
      echo "UNCLASSIFIED: $table — decide whether it is backed up (+) or never (-) in $SCOPE" >&2
      unclassified=1
    fi
  done < <(
    # Normalise first: one statement per line, comments stripped, so a
    # `create table\n  name` split across lines, an UNLOGGED table, a quoted or
    # schema-qualified name, or a digit-suffixed name (`rate_bucket2` is NOT
    # `rate_bucket`) all yield exactly their table name.
    cat "${files[@]}" | sed -E 's/--.*$//' | tr '\n' ' ' | tr ';' '\n' \
      | grep -ioE 'CREATE[[:space:]]+(UNLOGGED[[:space:]]+)?TABLE[[:space:]]+(IF[[:space:]]+NOT[[:space:]]+EXISTS[[:space:]]+)?("?[A-Za-z0-9_]+"?\.)?"?[A-Za-z0-9_]+"?' \
      | awk '{gsub(/"/, "", $NF); sub(/^.*\./, "", $NF); print tolower($NF)}' | sort -u)
  (( unclassified == 0 )) || fail "the schema has tables the backup scope does not classify"
  echo "every table in $dir is classified (${#KEEP[@]} kept, ${#DENY[@]} never backed up)"
  exit 0
fi

# --- a backup honours the classification ------------------------------------
input="${1:-}"; [[ -f "$input" ]] || usage

# Tables whose DATA is present. For a plain-SQL dump that is COPY or INSERT
# INTO; a CREATE TABLE alone is schema only and retains nothing.
declare -a WITH_DATA=()
if grep -qiE '^[[:space:]]*(COPY|INSERT[[:space:]]+INTO|CREATE[[:space:]]+(UNLOGGED[[:space:]]+)?TABLE)[[:space:]]' "$input"; then
  # Per line, case-folded, with one continuation form handled: a bare
  # `INSERT INTO` line is joined with its successor. A leading tab or spaces
  # before COPY/INSERT are still a data statement; a `\.` terminator, a data
  # row, or a CREATE TABLE are not.
  while IFS= read -r t; do [[ -n "$t" ]] && WITH_DATA+=("$t"); done < <(
    tr 'A-Z' 'a-z' < "$input" | awk '
      { if (pending) { $0 = "insert into " $0; pending = 0 } }
      /^[ \t]*insert[ \t]+into[ \t]*$/ { pending = 1; next }
      /^[ \t]*(copy|insert[ \t]+into)[ \t]+/ { print }
    ' | sed -E 's/^[[:space:]]*(copy|insert[[:space:]]+into)[[:space:]]+(only[[:space:]]+)?("?[a-z0-9_]+"?\.)?"?([a-z0-9_]+)"?.*/\4/' \
      | sort -u)
else
  while IFS= read -r t; do
    t="${t%%#*}"; t="${t// /}"; t="${t##*.}"; t="${t//\"/}"
    [[ -n "$t" ]] && WITH_DATA+=("$(printf '%s' "$t" | tr '[:upper:]' '[:lower:]')")
  done < "$input"
fi

# A backup that examines nothing is the positive-control failure: an empty
# dump, a wrong file, a list produced by a broken pipeline all look "clean".
(( ${#WITH_DATA[@]} > 0 )) || fail "the backup carries no table data at all — nothing was examined"

violations=0
for t in "${DENY[@]}"; do
  for present in "${WITH_DATA[@]}"; do
    if [[ "$present" == "$t" ]]; then
      echo "RETAINS CONTENT: $t carries data; it is never backed up (ADR-009 deletion promise)" >&2
      violations=1
    fi
  done
done
for t in "${KEEP[@]}"; do
  found=0
  for present in "${WITH_DATA[@]}"; do [[ "$present" == "$t" ]] && found=1; done
  if (( found == 0 )); then
    echo "MISSING: $t is durable control-plane state and must be in every backup" >&2
    violations=1
  fi
done
for present in "${WITH_DATA[@]}"; do
  if ! classified "$present"; then
    echo "UNCLASSIFIED: $present is in the backup but not in $SCOPE" >&2
    violations=1
  fi
done
(( violations == 0 )) || fail "the backup does not honour the data-lifecycle contract"
echo "backup scope OK: ${#KEEP[@]} control-plane table(s) present, no content or transient data retained"
