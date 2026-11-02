#!/usr/bin/env bash
# Migration data-movement lint (TI-DELIVERY-002). DEFENSE IN DEPTH ONLY.
#
# WHY: the internet-facing ingestion role reads only what
# deploy/database/roles.reference.sql names, and the Ops grant contract proves
# the applied grants. Neither can see a migration statement that COPIES
# identity, session or key data into a table the ingestion role is allowed to
# read: the grants stay exactly as reviewed while the data moves under them.
# The owner accepted that residual. Migration review (CODEOWNERS) is the trust
# boundary for it; this lint only puts the suspicious shapes in front of that
# review so they are not missed in a long diff.
#
# WHAT THIS IS NOT: a proof. Passing it does NOT show that a migration never
# copies sensitive data. It is a lexical scan, not a SQL parser or an
# evaluator. It cannot see, among others: dynamic SQL assembled from fragments
# ('human_' || 'account'), functions or triggers defined in an earlier
# migration and merely invoked by a new one, writes through a view, an
# extension or a foreign table, or data that is first copied into a
# non-identity table and then onward. Treat a green run as "nothing obvious",
# never as "safe".
#
# WHAT IT FLAGS (comments are ignored everywhere; string literals are ignored
# in ordinary statements but SCANNED inside function, procedure and DO bodies,
# because there a string may be dynamic SQL):
#   * data movement whose SOURCE names a sensitive table (list below):
#     INSERT … SELECT/VALUES(subquery), UPDATE … FROM / SET = (subquery),
#     MERGE … USING, SELECT … INTO, WITH … (data-modifying), CREATE TABLE … AS,
#     COPY, CREATE [MATERIALIZED] VIEW, CREATE RULE, PREPARE, and
#     CREATE/ALTER PUBLICATION (logical replication exports rows);
#   * a function, procedure or DO body that names a sensitive table;
#   * a trigger ON a sensitive table (its function sees NEW/OLD rows without
#     ever naming the table);
#   * dynamic SQL (EXECUTE) in a function, procedure or DO body: whatever it
#     builds at run time is invisible to this lint;
#   * a GRANT (or ALTER DEFAULT PRIVILEGES … GRANT, or OWNER TO) to
#     testinbox_ingestion, to any role whose name contains "ingestion", to
#     PUBLIC, or to a grantee that is not a literal role name. Grants belong
#     in deploy/database/roles.reference.sql. These are NOT suppressible.
# A GRANT inside a string in a routine body (dynamic GRANT) is flagged too,
# and, being a heuristic, can be allow-listed.
#
# SCOPE: migrations that are not yet released. Released ones are pinned
# instead: scripts/migration-lint/released-migrations.sha256 lists every
# released migration with its sha256. An edited, deleted or renamed released
# migration fails, and so does a new migration whose version is not after the
# newest released one. Pin a new migration (append `sha256sum V<n>__x.sql`)
# in the pull request that adds it, or in the next one; never re-hash an
# existing line. On a pull request CI passes the base's copy of the list as
# --released-base: the list is then append-only, and a migration pinned by
# the pull request itself is still linted. V1–V22 were reviewed when they
# shipped and are pinned, not re-linted; the test suite shows what the lint
# WOULD flag in them, as evidence from real history.
#
# ALLOW-LIST. A reviewed false positive is accepted by one line in
# scripts/migration-lint/allowlist.txt:
#     <migration file>  <statement hash>  <justification>
# The hash covers the statement with comments removed and whitespace
# collapsed, so editing the statement invalidates its entry and needs a new
# review. Unused entries fail (the list cannot silently go stale).
#
# Exit codes: 0 = no unreviewed finding, 1 = findings, 2 = usage/config error.
#
# Usage: scripts/check-migration-data-movement.sh
#            [--migrations DIR] [--released FILE] [--allowlist FILE]
#            [--released-base FILE]   (the released list at the PR base: every
#                                      line in it must still be present, unchanged)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS="$REPO_ROOT/backend/persistence/src/main/resources/db/migration"
RELEASED="$REPO_ROOT/scripts/migration-lint/released-migrations.sha256"
ALLOWLIST="$REPO_ROOT/scripts/migration-lint/allowlist.txt"
RELEASED_BASE=""

usage() {
  echo "usage: $(basename "$0") [--migrations DIR] [--released FILE] [--allowlist FILE] [--released-base FILE]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migrations) [[ $# -ge 2 ]] || usage; MIGRATIONS="$2"; shift 2 ;;
    --released) [[ $# -ge 2 ]] || usage; RELEASED="$2"; shift 2 ;;
    --allowlist) [[ $# -ge 2 ]] || usage; ALLOWLIST="$2"; shift 2 ;;
    --released-base) [[ $# -ge 2 ]] || usage; RELEASED_BASE="$2"; shift 2 ;;
    *) usage ;;
  esac
done

python3 -I - "$MIGRATIONS" "$RELEASED" "$ALLOWLIST" "$RELEASED_BASE" <<'PY'
import hashlib
import os
import re
import sys

migrations_dir, released_path, allowlist_path, released_base_path = sys.argv[1:5]
annotate = os.environ.get("GITHUB_ACTIONS") == "true"

# Identity, session and key data (TI-DELIVERY-002). Keep in step with the
# tables the ingestion role must never read (deploy/database/roles.reference.sql).
SENSITIVE = {
    "human_account", "human_identity", "human_session", "email_login_challenge",
    "provider_login_state", "api_key", "idempotency_record", "beta_admission",
    "beta_waitlist", "beta_cohort", "project", "lifecycle_restore_clearance",
}
INGESTION_ROLE = "testinbox_ingestion"

REMINDER = """\
REMINDER: this lint flags suspicious patterns for human review. Passing it does
NOT prove that a migration never copies identity, session or key data into a
table the ingestion role can read: dynamic SQL, functions defined earlier,
multi-step copies and anything else a lexical scan cannot see go straight
through. Migration review (CODEOWNERS) is the trust boundary; the Ops grant
contract cannot detect such a copy either. This is defense in depth only."""


class ConfigError(Exception):
    pass


class Token:
    __slots__ = ("kind", "value", "text", "line")

    def __init__(self, kind, value, text, line):
        self.kind, self.value, self.text, self.line = kind, value, text, line


IDENT_START = re.compile(r"[A-Za-z_\u0080-￿]")
IDENT_REST = re.compile(r"[A-Za-z0-9_$\u0080-￿]*")
DOLLAR_TAG = re.compile(r"\$([A-Za-z_\u0080-￿][A-Za-z0-9_\u0080-￿]*)?\$")


def tokenize(text, line=1):
    """Comments dropped; strings, quoted identifiers and dollar bodies kept whole."""
    tokens, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
        elif c.isspace():
            i += 1
        elif text.startswith("--", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            if depth:
                raise ConfigError(f"line {line}: unterminated block comment")
            line += text.count("\n", i, j)
            i = j
        elif c == "'":
            i, line = read_string(text, i, line, tokens, escapes=False)
        elif c == '"':
            j = i + 1
            while True:
                j = text.find('"', j)
                if j < 0:
                    raise ConfigError(f"line {line}: unterminated quoted identifier")
                if text.startswith('""', j):
                    j += 2
                    continue
                break
            raw = text[i + 1:j].replace('""', '"')
            tokens.append(Token("ident", raw.lower(), text[i:j + 1], line))
            line += text.count("\n", i, j)
            i = j + 1
        elif c == "$":
            m = DOLLAR_TAG.match(text, i)
            if m and not text[i + 1:i + 2].isdigit():
                tag = m.group(0)
                end = text.find(tag, m.end())
                if end < 0:
                    raise ConfigError(f"line {line}: unterminated dollar quote {tag}")
                tokens.append(Token("dollar", text[m.end():end], tag, line))
                line += text.count("\n", i, end + len(tag))
                i = end + len(tag)
            else:
                j = i + 1
                while j < n and text[j].isdigit():
                    j += 1
                tokens.append(Token("punct", text[i:j], text[i:j], line))
                i = j
        elif IDENT_START.match(c):
            m = IDENT_REST.match(text, i + 1)
            word = text[i:m.end()]
            if word.lower() in ("e", "b", "x", "n") and text[m.end():m.end() + 1] == "'":
                i, line = read_string(text, m.end(), line, tokens, escapes=word.lower() == "e")
            else:
                tokens.append(Token("word", word.lower(), word.lower(), line))
                i = m.end()
        elif c.isdigit():
            m = re.compile(r"[0-9.eE_]+").match(text, i)
            tokens.append(Token("num", m.group(0), m.group(0), line))
            i = m.end()
        else:
            tokens.append(Token("punct", c, c, line))
            i += 1
    return tokens


def read_string(text, i, line, tokens, escapes):
    j, n = i + 1, len(text)
    while True:
        if j >= n:
            raise ConfigError(f"line {line}: unterminated string literal")
        if escapes and text[j] == "\\":
            j += 2
            continue
        if text[j] == "'":
            if text.startswith("''", j):
                j += 2
                continue
            break
        j += 1
    tokens.append(Token("string", text[i + 1:j], text[i:j + 1], line))
    return j + 1, line + text.count("\n", i, j)


def is_word(token, *values):
    return token is not None and token.kind == "word" and token.value in values


def at(tokens, k):
    return tokens[k] if 0 <= k < len(tokens) else None


def split_statements(tokens):
    """Split on top-level `;`, keeping SQL-standard BEGIN ATOMIC … END bodies whole."""
    statements, current, atomic = [], [], 0
    for t in tokens:
        if atomic:
            if is_word(t, "begin", "case"):
                atomic += 1
            elif is_word(t, "end"):
                atomic -= 1
        elif is_word(t, "atomic") and current and is_word(current[-1], "begin"):
            atomic = 1
        if t.kind == "punct" and t.value == ";" and not atomic:
            if current:
                statements.append(current)
            current = []
        else:
            current.append(t)
    if current:
        statements.append(current)
    return statements


def normalized(tokens):
    parts = []
    for t in tokens:
        if t.kind == "dollar":
            parts.append(t.text + normalized(tokenize(t.value)) + t.text)
        else:
            parts.append(t.text)
    return " ".join(parts)


def statement_hash(tokens):
    return hashlib.sha256(normalized(tokens).encode("utf-8")).hexdigest()[:16]


def name_indices(tokens, k):
    """Indices of a possibly schema-qualified name starting at k."""
    out = []
    while at(tokens, k) is not None and tokens[k].kind in ("word", "ident"):
        out.append(k)
        if at(tokens, k + 1) is not None and tokens[k + 1].value == "." and tokens[k + 1].kind == "punct":
            k += 2
        else:
            break
    return out


def matching_paren(tokens, k):
    depth = 0
    for j in range(k, len(tokens)):
        if tokens[j].kind == "punct" and tokens[j].value == "(":
            depth += 1
        elif tokens[j].kind == "punct" and tokens[j].value == ")":
            depth -= 1
            if depth == 0:
                return j
    return len(tokens) - 1


def depth_zero(tokens):
    depth, out = 0, []
    for t in tokens:
        if t.kind == "punct" and t.value == "(":
            depth += 1
        elif t.kind == "punct" and t.value == ")":
            depth -= 1
        out.append(depth == 0)
    return out


SENSITIVE_IN_TEXT = re.compile(r"(?<![A-Za-z0-9_$])(" + "|".join(sorted(SENSITIVE)) + r")(?![A-Za-z0-9_$])", re.IGNORECASE)


def refs(tokens, skip=(), deep=False):
    """(table, line) for every sensitive name. Deep: also inside bodies and strings."""
    found = []
    for k, t in enumerate(tokens):
        if k in skip:
            continue
        if t.kind in ("word", "ident") and t.value in SENSITIVE:
            found.append((t.value, t.line))
        elif deep and t.kind == "dollar":
            found.extend(refs(tokenize(t.value, t.line), deep=True))
        elif deep and t.kind == "string":
            for m in SENSITIVE_IN_TEXT.finditer(t.value):
                found.append((m.group(1).lower(), t.line + t.value.count("\n", 0, m.start())))
    return found


def body_tokens(tokens):
    """Tokens of every dollar-quoted body in the statement (recursively)."""
    out = []
    for t in tokens:
        if t.kind == "dollar":
            inner = tokenize(t.value, t.line)
            out.extend(inner)
            out.extend(body_tokens(inner))
    return out


def body_strings(tokens):
    return [t for t in tokens + body_tokens(tokens) if t.kind == "string"]


def dynamic_sql(tokens):
    found = []
    inner = body_tokens(tokens)
    for k, t in enumerate(inner):
        if is_word(t, "execute") and not is_word(at(inner, k + 1), "function", "procedure"):
            found.append(t.line)
    return found


def grant_findings(tokens):
    """GRANT / ALTER DEFAULT PRIVILEGES … GRANT / OWNER TO with a forbidden grantee."""
    out = []
    zero = depth_zero(tokens)
    first = tokens[0] if tokens else None
    grantee_start = None
    if is_word(first, "grant") or (is_word(first, "alter") and any(is_word(t, "grant") for t in tokens)):
        for k in range(len(tokens) - 1, -1, -1):
            if zero[k] and is_word(tokens[k], "to"):
                grantee_start = k + 1
                break
        what = "GRANT"
    elif is_word(first, "alter"):
        for k in range(len(tokens) - 1):
            if is_word(tokens[k], "owner") and is_word(tokens[k + 1], "to"):
                grantee_start = k + 2
        what = "OWNER TO"
    if grantee_start is None:
        return out
    group = []
    groups = [group]
    for t in tokens[grantee_start:]:
        if is_word(t, "with", "granted"):
            break
        if t.kind == "punct" and t.value == ",":
            group = []
            groups.append(group)
        elif not is_word(t, "group"):
            group.append(t)
    for g in groups:
        line = g[0].line if g else tokens[0].line
        if len(g) == 1 and g[0].kind in ("word", "ident"):
            role = g[0].value
            if role == "public" and g[0].kind == "word":
                out.append((line, f"{what} to PUBLIC — every role, the ingestion role included, receives it"))
            elif role == INGESTION_ROLE or "ingestion" in role:
                out.append((line, f"{what} to {role} — ingestion grants belong in deploy/database/roles.reference.sql"))
        else:
            text = " ".join(t.text for t in g) or "(nothing)"
            out.append((line, f"{what} to {text} — not a literal role name, so it may be the ingestion role or PUBLIC"))
    return out


def classify(tokens):
    """[(rule, line, message, suppressible)] for one top-level statement."""
    findings = []
    first = tokens[0]
    words = [t.value for t in tokens if t.kind == "word"]
    zero = depth_zero(tokens)

    def flag(rule, found, verb):
        for table, line in found:
            findings.append((rule, line, f"{verb} reads {table}", True))

    if first.kind == "word" and first.value in ("insert", "update", "merge", "with", "select", "values", "table"):
        skip, modifying = set(), False
        for k, t in enumerate(tokens):
            prev = at(tokens, k - 1)
            if is_word(t, "insert", "merge") and is_word(at(tokens, k + 1), "into"):
                modifying = True
                names = name_indices(tokens, k + 2)
                skip.update(names)
                after = (names[-1] + 1) if names else k + 2
                nxt = at(tokens, after)
                if nxt is not None and nxt.kind == "punct" and nxt.value == "(" \
                        and not is_word(at(tokens, after + 1), "select", "with", "values", "table"):
                    skip.update(range(after, matching_paren(tokens, after) + 1))
            elif is_word(t, "update") and not is_word(prev, "do", "then", "for", "key", "share", "of", "or", "before", "after"):
                modifying = True
                k2 = k + 2 if is_word(at(tokens, k + 1), "only") else k + 1
                skip.update(name_indices(tokens, k2))
            elif is_word(t, "into") and not is_word(prev, "insert", "merge"):
                modifying = True
                k2 = k + 1
                while is_word(at(tokens, k2), "temp", "temporary", "unlogged", "table"):
                    k2 += 1
                skip.update(name_indices(tokens, k2))
        if modifying:
            flag("data-movement", refs(tokens, skip), f"{first.value.upper()} statement (data movement)")
    elif is_word(first, "copy"):
        flag("data-movement", refs(tokens), "COPY")
    elif is_word(first, "prepare"):
        flag("data-movement", refs(tokens), "PREPARE (a statement executed later)")
    elif is_word(first, "do"):
        flag("routine-body", refs(tokens, deep=True), "DO block body")
        for line in dynamic_sql(tokens):
            findings.append(("dynamic-sql", line, "dynamic SQL (EXECUTE) in a DO block — what it builds is invisible to this lint", True))
    elif is_word(first, "create"):
        k = 1
        while is_word(at(tokens, k), "or", "replace", "temp", "temporary", "unlogged", "global", "local",
                      "recursive", "materialized", "constraint", "trusted", "procedural"):
            k += 1
        kind = at(tokens, k)
        k += 1
        while is_word(at(tokens, k), "if", "not", "exists"):
            k += 1
        name = set(name_indices(tokens, k))
        if is_word(kind, "table"):
            for j in range(k, len(tokens)):
                if zero[j] and is_word(tokens[j], "as"):
                    flag("data-movement", refs(tokens[j + 1:]), "CREATE TABLE … AS")
                    break
        elif is_word(kind, "view"):
            label = "CREATE MATERIALIZED VIEW" if "materialized" in words[:k] else "CREATE VIEW"
            flag("data-movement", refs(tokens, name), label)
        elif is_word(kind, "function", "procedure"):
            flag("routine-body", refs(tokens, name, deep=True), f"{kind.value.upper()} body or signature")
            for line in dynamic_sql(tokens):
                findings.append(("dynamic-sql", line, f"dynamic SQL (EXECUTE) in a {kind.value} body — what it builds is invisible to this lint", True))
        elif is_word(kind, "trigger"):
            for table, line in refs(tokens, name):
                findings.append(("trigger", line, f"trigger on/referencing {table} — its function sees whole rows without naming the table", True))
        elif is_word(kind, "rule"):
            flag("data-movement", refs(tokens, name), "CREATE RULE")
        elif is_word(kind, "publication"):
            flag("data-movement", refs(tokens, name), "CREATE PUBLICATION (logical replication)")
    elif is_word(first, "alter") and is_word(at(tokens, 1), "publication"):
        flag("data-movement", refs(tokens, set(name_indices(tokens, 2))), "ALTER PUBLICATION (logical replication)")

    for line, message in grant_findings(tokens):
        findings.append(("grant", line, message, False))
    # GRANTs inside routine bodies: as code, and as dynamic SQL in strings.
    if is_word(first, "do") or (is_word(first, "create") and any(is_word(t, "function", "procedure") for t in tokens[:6])):
        for inner in split_statements(body_tokens(tokens)):
            # PL/pgSQL statements may follow BEGIN / THEN / ELSE / LOOP on the same "statement".
            for k, t in enumerate(inner):
                if is_word(t, "grant"):
                    for line, message in grant_findings(inner[k:]):
                        findings.append(("grant", line, message, False))
                    break
        for s in body_strings(tokens):
            for m in re.finditer(r"\bgrant\b", s.value, re.IGNORECASE):
                findings.append(("grant", s.line + s.value.count("\n", 0, m.start()),
                                 "GRANT inside a string in a routine body (dynamic GRANT) — the grantee cannot be checked", True))
    return findings


VERSION = re.compile(r"^V([0-9]+(?:[._][0-9]+)*)__.+\.sql$")


def version_of(name):
    m = VERSION.match(name)
    return tuple(int(p) for p in re.split(r"[._]", m.group(1))) if m else None


def load_released(path):
    released = {}
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"^([0-9a-f]{64})\s+\*?(\S+)$", line)
            if not m or version_of(m.group(2)) is None:
                raise ConfigError(f"{path}:{number}: expected '<sha256>  V<n>__<name>.sql'")
            if m.group(2) in released:
                raise ConfigError(f"{path}:{number}: {m.group(2)} listed twice")
            released[m.group(2)] = m.group(1)
    return released


def load_allowlist(path):
    entries = {}
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 2)
            if len(parts) < 3 or not re.fullmatch(r"[0-9a-f]{16}", parts[1]):
                raise ConfigError(f"{path}:{number}: expected '<migration file>  <16-hex statement hash>  <justification>'")
            if len(parts[2]) < 30:
                raise ConfigError(f"{path}:{number}: the justification must say why this statement does not expose sensitive data")
            key = (parts[0], parts[1])
            if key in entries:
                raise ConfigError(f"{path}:{number}: duplicate entry {parts[0]} {parts[1]}")
            entries[key] = number
    return entries


def main():
    if not os.path.isdir(migrations_dir):
        raise ConfigError(f"no such migrations directory: {migrations_dir}")
    files = sorted(f for f in os.listdir(migrations_dir) if f.endswith(".sql"))
    if not files:
        raise ConfigError(f"no migrations found in {migrations_dir} — the lint would scan nothing")
    released = load_released(released_path)
    allowlist = load_allowlist(allowlist_path)

    errors = []
    present = set(files)
    newest = max((version_of(name) for name in released), default=None)
    for name, digest in sorted(released.items(), key=lambda item: version_of(item[0])):
        if name not in present:
            errors.append((name, 1, "immutability", f"released migration {name} is missing (deleted or renamed)"))
            continue
        with open(os.path.join(migrations_dir, name), "rb") as f:
            actual = hashlib.sha256(f.read()).hexdigest()
        if actual != digest:
            errors.append((name, 1, "immutability",
                           f"released migration {name} was edited (sha256 {actual[:12]}…, released {digest[:12]}…); "
                           "released migrations are immutable — add a new migration instead"))
    for name in files:
        if name in released:
            continue
        v = version_of(name)
        if v is not None and newest is not None and v <= newest:
            errors.append((name, 1, "immutability",
                          f"{name} is not released but its version is not after the newest released migration "
                          f"V{'.'.join(map(str, newest))}"))

    # Released migrations are pinned byte-for-byte above and were reviewed when
    # they were released, so the pattern scan covers only what is not. With the
    # PR base's list, "released" means released AT THE BASE: a migration this
    # change adds and pins in the same change is still linted here.
    exempt = set(released)
    if released_base_path:
        # The released list itself may only grow: every line the PR base had
        # must still be there, byte-for-byte, so a PR cannot edit a released
        # migration and re-hash its line in the same change.
        base = load_released(released_base_path)
        for name, digest in base.items():
            if released.get(name) != digest:
                errors.append((name, 1, "immutability",
                               f"{name} was released with sha256 {digest[:12]}… at the base of this change, and "
                               f"{os.path.basename(released_path)} no longer says so; released lines are append-only"))
        exempt &= set(base)

    findings, used, scanned = [], set(), 0
    for name in files:
        if name in exempt:
            continue
        with open(os.path.join(migrations_dir, name), encoding="utf-8") as f:
            text = f.read()
        try:
            statements = split_statements(tokenize(text))
        except ConfigError as e:
            errors.append((name, 1, "parse", f"cannot tokenize: {e}"))
            continue
        scanned += 1
        for stmt in statements:
            results = classify(stmt)
            if not results:
                continue
            digest = statement_hash(stmt)
            allowed = (name, digest) in allowlist
            if allowed and any(s for _, _, _, s in results):
                used.add((name, digest))
            for rule, line, message, suppressible in results:
                if allowed and suppressible:
                    continue
                findings.append((name, line, rule, f"{message} (statement {digest}, starts line {stmt[0].line})"))

    # An entry for a released (pinned, no longer scanned) migration is inert,
    # not stale: it was reviewed while the migration was linted, and the
    # migration cannot change any more.
    for (name, digest), number in sorted(allowlist.items(), key=lambda item: item[1]):
        if (name, digest) not in used and (name not in exempt or name not in present):
            errors.append((os.path.basename(allowlist_path), number, "stale-allowlist",
                           f"allow-list entry {name} {digest} matches no flagged statement; remove it"))

    problems = sorted(errors + findings, key=lambda p: (version_of(p[0]) or (10**9,), p[0], p[1]))
    if problems:
        print(f"migration data-movement lint FAILED: {len(problems)} finding(s) need human review", file=sys.stderr)
        for name, line, rule, message in problems:
            print(f"  {name}:{line}: [{rule}] {message}", file=sys.stderr)
            if annotate:
                path = os.path.join(migrations_dir, name)
                print(f"::error file={os.path.relpath(path)},line={line}::[{rule}] {message}")
        if findings:
            print("", file=sys.stderr)
            print("A reviewer who has read the statement and is sure it exposes no identity, session or key", file=sys.stderr)
            print(f"data to the ingestion role may accept it with one line in {allowlist_path}:", file=sys.stderr)
            print("    <migration file>  <statement hash>  <justification>", file=sys.stderr)
            print("GRANT findings cannot be allow-listed: grants belong in deploy/database/roles.reference.sql.", file=sys.stderr)
        print("", file=sys.stderr)
        print(REMINDER, file=sys.stderr)
        return 1
    print(f"migration data-movement lint: {scanned} unreleased migration(s) scanned, {len(released)} released migration(s) "
          f"unchanged, {len(used)} reviewed allow-list entr{'y' if len(used) == 1 else 'ies'} in use, no unreviewed finding.")
    print(REMINDER)
    return 0


try:
    sys.exit(main())
except (ConfigError, OSError, UnicodeDecodeError) as e:
    print(f"migration data-movement lint: configuration error: {e}", file=sys.stderr)
    sys.exit(2)
PY
