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
# A plain-SQL dump is read statement by statement, not line by line: a
# `COPY`/`INSERT INTO` counts only where it begins a top-level statement.
# Text inside string literals, dollar-quoted function bodies, comments and
# COPY data blocks is opaque — the ADR-035 trigger functions carry
# `INSERT INTO` lines in their bodies and retain nothing. Anything the reader
# cannot account for (an unterminated quote, body or COPY block, a data
# statement whose table cannot be named, a stray `\.`, a truncated statement)
# is refused rather than ignored: ambiguity never passes.
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

# Tables whose classification is not a choice: content (ADR-009) and the
# ADR-035 accounting and admission state, which is derived or transient and
# rebuilt after a restore. Classifying any of them `+` would put content, or
# state that describes an object store that is not backed up, into a backup,
# so the scope file itself is refused.
REQUIRED_DENY=(inbox message attachment human_session email_login_challenge human_auth_gate
               workspace_storage_account inbox_storage storage_delta
               storage_reservation storage_ambiguity storage_node storage_admission_latch
               storage_clock_episode storage_deletion_debt storage_filesystem_observation)
for required in "${REQUIRED_DENY[@]}"; do
  for kept in "${KEEP[@]}"; do
    [[ "$kept" == "$required" ]] && { echo "SCOPE MISCLASSIFIED: $required is content or derived state and must be '-' in $SCOPE" >&2; exit 1; }
  done
done

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

# A custom/tar archive is binary; its table list is what the documented
# `pg_restore -l` recipe produces. Refuse the archive itself rather than
# reading its bytes as names.
if head -c 5 "$input" | grep -q '^PGDMP'; then
  fail "$input is a pg_dump archive, not plain SQL — list its TABLE DATA entries with: pg_restore -l dump | awk '/ TABLE DATA / {print \$7}'"
fi

# A raw `pg_restore -l` listing is neither documented input; name it and the
# recipe rather than reading its lines as SQL.
if grep -qE '^[0-9]+; [0-9]+ [0-9]+ ' "$input"; then
  fail "$input looks like a raw 'pg_restore -l' listing — apply the recipe: pg_restore -l dump | awk '/ TABLE DATA / {print \$7}'"
fi

# Which of the two documented inputs is this? A table list is nothing but
# names: one `[schema.]table` per line, optionally quoted, `#` comments.
# Anything else is read as SQL.
is_table_list() {
  ! grep -vqE '^[[:space:]]*(#.*)?$|^[[:space:]]*"?[A-Za-z0-9_]+"?(\."?[A-Za-z0-9_]+"?)?[[:space:]]*(#.*)?$' "$input"
}

# The SQL reader. One statement at a time, with the lexical states a plain
# dump can be in: a single-quoted string (standard or E'' escaping), a quoted
# identifier, a dollar-quoted body ($$ or $tag$), a `--` or `/* */` comment,
# the data block that follows `COPY ... FROM stdin;` up to its `\.`, and a
# psql meta-command line (`\restrict`, `\connect`, `\copy`). Only a top-level
# statement beginning `COPY <table>` / `INSERT INTO <table>` (or a `\copy`
# line) names a table that carries data. Output: `DATA <table>` per data
# statement, or `AMBIGUOUS <reason>` for input the reader cannot account for.
read_sql_dump() {
  awk '
    function ambiguous(why) { print "AMBIGUOUS\t" why }
    # One identifier at the start of s: bare, or "quoted" with only the
    # characters a table name in this schema can have. Sets ident_len.
    function identifier(s) {
      if (match(s, /^"[A-Za-z0-9_]+"/)) { ident_len = RLENGTH; return substr(s, 2, RLENGTH - 2) }
      if (match(s, /^[A-Za-z_][A-Za-z0-9_]*/)) { ident_len = RLENGTH; return substr(s, 1, RLENGTH) }
      ident_len = 0; return ""
    }
    # The table a data statement names, from the text after its keyword(s).
    function table_of(rest,   name) {
      sub(/^[ \t]+/, "", rest)
      if (match(rest, /^[Oo][Nn][Ll][Yy][ \t]+/)) rest = substr(rest, RLENGTH + 1)
      name = identifier(rest)
      if (name == "") return ""
      rest = substr(rest, ident_len + 1)
      if (substr(rest, 1, 1) == ".") {
        name = identifier(substr(rest, 2))
        if (name == "") return ""
      }
      return tolower(name)
    }
    function data_statement(kind, rest,   t) {
      t = table_of(rest)
      if (t == "") { ambiguous(kind " statement whose table cannot be named: " substr(kind " " rest, 1, 80)); return }
      print "DATA\t" t
    }
    # A statement is accounted for only if it is one a plain dump contains:
    # the two data forms, or DDL/session statements that retain no rows and
    # run nothing at restore time. Anything else — a statement that
    # materialises, fetches or creates rows (CREATE TABLE ... AS, SELECT ...
    # INTO, MERGE, a data-modifying CTE, a DO block, a function call, UPDATE,
    # a materialized view, a subscription, a rule), one that evaluates a
    # user function at restore (a schema-qualified call outside a function
    # or trigger definition; search_path is kept empty so an unqualified one
    # cannot resolve), data placed after a trigger definition (never
    # pg_dump order), or a statement this reader does not know — is
    # refused: it is not for the gate to guess what it retains.
    # A schema-qualified name followed by "(" is a function call, unless the
    # name is the object of the statement and the "(" opens its column list
    # (CREATE TABLE public.t (...), INSERT INTO public.t (...), ... ON
    # public.t (...), REFERENCES public.t (...), PARTITION OF public.t (...)).
    # allow_on: "ON public.t (" introduces the object only in CREATE INDEX,
    # COMMENT ON and GRANT/REVOKE; elsewhere (a join predicate) it is a call.
    function user_call(ls, allow_on,   rest, pre, w) {
      rest = ls
      while (match(rest, /[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*[ ]*\(/)) {
        pre = substr(rest, 1, RSTART - 1); sub(/ +$/, "", pre)
        w = pre; sub(/^.* /, "", w)
        # ... or a function signature: GRANT/REVOKE ON FUNCTION f(args),
        # ALTER FUNCTION f(args), COMMENT ON FUNCTION f(args).
        if (w == "on" && allow_on) { rest = substr(rest, RSTART + RLENGTH); continue }
        if (w !~ /^(table|into|references|of|only|exists|copy|function|procedure|routine|aggregate)$/) return 1
        rest = substr(rest, RSTART + RLENGTH)
      }
      return 0
    }
    # pg_dump empties search_path in its prologue (and again after \connect);
    # until that statement has been read, no DDL or data is accounted for,
    # because an unqualified user function would otherwise resolve at restore.
    function needs_prologue(s) {
      if (sp_empty) return 0
      ambiguous("DDL or data before the search_path was emptied (pg_dump prologue): " substr(s, 1, 80)); return 1
    }
    function finish_statement(   s, ls, q) {
      s = stmt; stmt = ""
      if (overflow) { overflow = 0; ambiguous("a statement longer than the reader accounts for (" cap " bytes): " substr(s, 1, 80)); return }
      gsub(/[ \t\r]+/, " ", s); sub(/^ /, "", s); sub(/ $/, "", s)
      if (s == "") return
      ls = tolower(s)
      gsub(/\(/, " ( ", ls); gsub(/\)/, " ) ", ls); gsub(/  +/, " ", ls); sub(/ $/, "", ls)
      # "schema . name" is "schema.name" to PostgreSQL; read it that way.
      while (sub(/ \./, ".", ls)) {}
      while (sub(/\. /, ".", ls)) {}
      # Quoted identifiers: only the alphabet a name in this schema can have,
      # then read without their quotes so a quoted name is not a different name.
      q = ls; gsub(/"[a-z0-9_]+"/, "", q)
      if (index(q, "\"")) { ambiguous("a quoted identifier outside the schema alphabet: " substr(s, 1, 80)); return }
      gsub(/"/, "", ls)
      if (ls ~ /^copy /) {
        if (needs_prologue(s)) return
        if (ls !~ / from stdin( |$)/) { ambiguous("a COPY that does not read from stdin (a file or a program the reader never sees): " substr(s, 1, 80)); return }
        if (triggers_seen) { ambiguous("table data after a trigger definition — not pg_dump order, and the trigger would run on restore: " substr(s, 1, 80)); return }
        data_statement("COPY", substr(s, 6))
        mode = "copydata"
        return
      }
      if (ls ~ /^insert into /) {
        if (needs_prologue(s)) return
        if (triggers_seen) { ambiguous("table data after a trigger definition — not pg_dump order, and the trigger would run on restore: " substr(s, 1, 80)); return }
        # pg_dump --inserts emits only INSERT INTO t [(cols)] [OVERRIDING ... VALUE] VALUES (...) [, (...)] / DEFAULT VALUES.
        if (ls !~ /^insert into [a-z0-9_.]+( \( [a-z0-9_, ]+ \))?( overriding (system|user) value)?( values \( | default values$)/) { ambiguous("an INSERT that is not the VALUES form pg_dump emits (a query could run a function): " substr(s, 1, 80)); return }
        if (user_call(ls, 0)) { ambiguous("a row that calls a function at restore: " substr(s, 1, 80)); return }
        sub(/^[Ii][Nn][Ss][Ee][Rr][Tt][ \t]+[Ii][Nn][Tt][Oo][ \t]+/, "", s)
        data_statement("INSERT INTO", s)
        return
      }
      if (ls ~ /^(create|alter|comment|grant|revoke|drop|security label) / && needs_prologue(s)) return
      # With search_path empty an unqualified name resolves only in pg_catalog,
      # so nothing may be created or moved there.
      if (ls ~ /^create (or replace )?(function|procedure) pg_catalog\./ || ls ~ / set schema pg_catalog( |$)/) { ambiguous("a function created or moved into pg_catalog, where an unqualified call would find it: " substr(s, 1, 80)); return }
      if (ls ~ /^create (or replace )?(function|procedure) /) return
      if (ls ~ /^create (constraint )?trigger /) { triggers_seen = 1; return }
      if (ls ~ /^create ((global |local )?(temp|temporary) |unlogged )?table /) {
        # CREATE TABLE ... AS <query> materialises rows; a generated column
        # ("GENERATED ALWAYS AS ( expr ) STORED") is the one other AS in DDL.
        gsub(/generated always as \( /, "generated always ( ", ls)
        if (ls ~ / as (select|values|table|with|execute|\( )/) { ambiguous("a CREATE TABLE ... AS that materialises rows: " substr(s, 1, 80)); return }
        if (user_call(ls, 0)) { ambiguous("DDL that calls a user function at restore: " substr(s, 1, 80)); return }
        return
      }
      if (ls ~ /^create (unique )?index /) {
        if (user_call(ls, 1)) { ambiguous("an index expression that calls a user function at restore: " substr(s, 1, 80)); return }
        return
      }
      if (ls ~ /^create (sequence|schema|extension|database) /) { if (user_call(ls, 0)) { ambiguous("DDL that calls a user function at restore: " substr(s, 1, 80)); return } return }
      if (ls ~ /^create type [a-z0-9_".]+ as (enum )?\( /) return
      if (ls ~ /^create /) { ambiguous("a CREATE outside the kinds a plain dump of this schema contains (table, function, trigger, index, sequence, enum/composite type, schema, extension, database): " substr(s, 1, 80)); return }
      if (ls ~ /^select /) {
        # The only SELECTs a plain dump contains set the session or a sequence,
        # with literal arguments (the lexer keeps a literal as 'S' or '').
        if (ls ~ ("^select (pg_catalog\\.)?set_config \\( " P ", " E ", false \\)$")) { sp_empty = 1; return }
        if (ls ~ ("^select (pg_catalog\\.)?set_config \\( " P ", " E ", true \\)$")) return
        if (ls ~ ("^select (pg_catalog\\.)?set_config \\( " S ", (" S "|" E "), (true|false) \\)$")) return
        if (ls ~ ("^select (pg_catalog\\.)?setval \\( " S ", -?[0-9]+(, (true|false))? \\)$")) return
        ambiguous("a SELECT that is not pg_dump literal set_config/setval (it may create rows, or run a function that does): " substr(s, 1, 80))
        return
      }
      if (ls ~ /^set search_path/) {
        if (ls !~ ("^set search_path (=|to) " E "$")) { ambiguous("a search_path that would let an unqualified user function resolve at restore: " substr(s, 1, 80)); return }
        sp_empty = 1; return
      }
      if (ls ~ /^reset (search_path|all)( |$)/) { sp_empty = 0; return }
      if (ls ~ /^set local /) { ambiguous("a transaction-local setting: " substr(s, 1, 80)); return }
      if (ls ~ /^set session search_path/) { ambiguous("a SET SESSION search_path: " substr(s, 1, 80)); return }
      if (ls ~ /^(set|reset) /) return
      if (ls ~ /^alter /) {
        if (ls ~ / enable (always |replica )?(trigger|rule) /) { ambiguous("an ALTER that enables a trigger or rule for the rest of the restore: " substr(s, 1, 80)); return }
        if (ls ~ / set search_path/) { ambiguous("an ALTER that sets a search_path: " substr(s, 1, 80)); return }
        if (user_call(ls, 0)) { ambiguous("an ALTER that calls a user function at restore (a default, a constraint): " substr(s, 1, 80)); return }
        return
      }
      if (ls ~ /^(begin|start transaction|commit|end|rollback|savepoint|release)( |$)/) { ambiguous("a transaction statement — pg_dump emits none, and a rolled-back prologue would be read as kept: " substr(s, 1, 80)); return }
      if (ls ~ /^(comment|grant|revoke|drop|lock|security label) /) {
        if (user_call(ls, 1)) { ambiguous("a statement that calls a user function at restore: " substr(s, 1, 80)); return }
        return
      }
      ambiguous("a statement outside the documented plain-dump forms: " substr(s, 1, 80))
    }
    function append(c) { if (length(stmt) < cap) stmt = stmt c; else overflow = 1 }
    function prev_is_ident_char(   p) {
      p = substr(stmt, length(stmt), 1)
      return (p ~ /[A-Za-z0-9_]/)
    }
    # E before the opening quote (and not part of a longer word) means
    # backslash escapes inside the string.
    function escape_string_prefix(   n, p, q) {
      n = length(stmt); if (n == 0) return 0
      p = substr(stmt, n, 1); if (p != "e" && p != "E") return 0
      if (n == 1) return 1
      q = substr(stmt, n - 1, 1); return (q !~ /[A-Za-z0-9_]/)
    }
    BEGIN { mode = "top"; stmt = ""; tag = ""; estring = 0; ident_len = 0; triggers_seen = 0; sq_nonempty = 0
            cap = 65536; overflow = 0; sp_empty = 0; sq_text = ""
            P = "\047search_path\047"
            S = "\047s\047"; E = "\047\047" }   # a non-empty and an empty literal as the lexer keeps them, lower-cased
    {
      line = $0; sub(/\r$/, "", line)
      if (NR == 1) sub(/^\357\273\277/, "", line)   # a UTF-8 byte-order mark
      if (mode == "copydata") {
        if (line == "\\.") mode = "top"
        next
      }
      n = length(line); i = 1
      # A psql meta-command occupies a whole line and has no terminator.
      # Those pg_dump emits are accounted for (\restrict, \unrestrict, and
      # \connect with --create); one that pulls in text the reader never
      # sees (\i), rewrites statements after they were read (\set and psql
      # variable interpolation) or runs anything (\!, \g*, \o) is refused
      # like any unknown statement.
      if (mode == "top" && stmt ~ /^[ \t]*$/ && line ~ /^[ \t]*\\/) {
        ml = line; sub(/^[ \t]+/, "", ml)
        if (ml == "\\.") { ambiguous("a COPY terminator outside any COPY data block"); next }
        if (tolower(ml) ~ /^\\copy[ \t]/) {
          sub(/^\\[Cc][Oo][Pp][Yy][ \t]+/, "", ml)
          if (tolower(ml) !~ /[ \t]from[ \t]+stdin([ \t]|$)/) { ambiguous("a \\copy that does not read from stdin: " substr(ml, 1, 80)); next }
          if (triggers_seen) { ambiguous("table data after a trigger definition — not pg_dump order: " substr(ml, 1, 80)); next }
          if (needs_prologue(ml)) next
          data_statement("\\copy", ml)
          mode = "copydata"
          next
        }
        if (tolower(ml) ~ /^\\(restrict|unrestrict)([ \t]|$)/) next
        if (tolower(ml) ~ /^\\(connect|c)[ \t]+"?[a-z0-9_]+"?[ \t]*$/) { sp_empty = 0; next }
        if (tolower(ml) ~ /^\\(connect|c)([ \t]|$)/) { ambiguous("a \\connect with more than a bare database name (a conninfo string resets the session): " substr(ml, 1, 80)); next }
        ambiguous("a psql meta-command outside the documented plain-dump forms: " substr(ml, 1, 80))
        next
      }
      while (i <= n) {
        c = substr(line, i, 1)
        if (mode == "dollar") {
          k = index(substr(line, i), tag)
          if (k == 0) break
          i += k - 1 + length(tag); mode = "top"; append(" "); continue
        }
        if (mode == "bcomment") {
          k = index(substr(line, i), "*/")
          if (k == 0) break
          i += k + 1; mode = "top"; append(" "); continue
        }
        if (mode == "squote") {
          if (c == "\\" && estring) { sq_nonempty = 1; sq_text = sq_text "\\"; i += 2; continue }
          if (c == "\047") {
            if (substr(line, i + 1, 1) == "\047") { sq_nonempty = 1; sq_text = sq_text "\047"; i += 2; continue }
            # A literal is kept only as '' or 'S': enough to recognise the
            # session statements pg_dump emits, never a place for keywords.
            if (tolower(sq_text) == "search_path") append("\047search_path\047")
            else append(sq_nonempty ? "\047S\047" : "\047\047")
            mode = "top"; i++; continue
          }
          sq_nonempty = 1; if (length(sq_text) < 16) sq_text = sq_text c; i++; continue
        }
        if (mode == "dquote") {
          if (c == "\"") {
            if (substr(line, i + 1, 1) == "\"") { append("\"\""); i += 2; continue }
            mode = "top"; append("\""); i++; continue
          }
          append(c); i++; continue
        }
        # mode == top
        if (c == "-" && substr(line, i + 1, 1) == "-") break
        if (c == "/" && substr(line, i + 1, 1) == "*") { mode = "bcomment"; i += 2; continue }
        if (c == "\047") { estring = escape_string_prefix(); sq_nonempty = 0; sq_text = ""; mode = "squote"; i++; continue }
        if (c == "\"") { mode = "dquote"; append("\""); i++; continue }
        if (c == "$" && !prev_is_ident_char() && match(substr(line, i), /^\$([A-Za-z_][A-Za-z0-9_]*)?\$/)) {
          tag = substr(line, i, RLENGTH); mode = "dollar"; i += RLENGTH; continue
        }
        if (c == ";") { finish_statement(); i++; continue }
        append(c); i++
      }
      if (mode == "top") append(" ")
      else if (mode == "squote") { sq_nonempty = 1; sq_text = sq_text "\n" }
    }
    END {
      if (mode == "squote") ambiguous("an unterminated string literal at end of input")
      else if (mode == "dquote") ambiguous("an unterminated quoted identifier at end of input")
      else if (mode == "dollar") ambiguous("an unterminated dollar-quoted body (" tag ") at end of input")
      else if (mode == "bcomment") ambiguous("an unterminated comment at end of input")
      else if (mode == "copydata") ambiguous("a COPY data block without its \\. terminator")
      s = stmt; gsub(/[ \t\r]+/, "", s)
      if (s != "" || overflow) ambiguous("a trailing statement without its terminator: " substr(stmt, 1, 80))
    }
  ' "$input"
}

# Tables whose DATA is present. For a plain-SQL dump that is a top-level COPY
# or INSERT INTO; a CREATE TABLE alone is schema only and retains nothing.
declare -a WITH_DATA=() AMBIGUITIES=()
if is_table_list; then
  while IFS= read -r t; do
    t="${t%%#*}"; t="${t// /}"; t="${t##*.}"; t="${t//\"/}"
    [[ -n "$t" ]] && WITH_DATA+=("$(printf '%s' "$t" | tr '[:upper:]' '[:lower:]')")
  done < "$input"
else
  while IFS=$'\t' read -r kind value; do
    case "$kind" in
      DATA) WITH_DATA+=("$value") ;;
      AMBIGUOUS) AMBIGUITIES+=("$value") ;;
      *) AMBIGUITIES+=("unreadable reader output: $kind") ;;
    esac
  done < <(read_sql_dump)
  if (( ${#AMBIGUITIES[@]} > 0 )); then
    for why in "${AMBIGUITIES[@]}"; do echo "AMBIGUOUS INPUT: $why" >&2; done
    fail "the dump contains input the reader cannot account for; it is not accepted"
  fi
fi
# De-duplicate: pg_dump --inserts names a table once per row.
if (( ${#WITH_DATA[@]} > 0 )); then
  declare -a WITH_DATA_UNIQUE=()
  while IFS= read -r t; do WITH_DATA_UNIQUE+=("$t"); done < <(printf '%s\n' "${WITH_DATA[@]}" | sort -u)
  WITH_DATA=("${WITH_DATA_UNIQUE[@]}")
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
