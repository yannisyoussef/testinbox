#!/usr/bin/env bash
# Lexical guard over deploy/database/roles.reference.sql (TI-DELIVERY-002).
#
# WHY: Ops applies this file and then proves the result with its own grant
# contract (infinity-core infra/testinbox, `grants-assert.sql` /
# `apply-app-grants.sh`). Their security review recorded two weaknesses, MD1 and
# md1 (PRODUCTION.md, "Known limitations"): a `DO` block in the reference, or a
# pre-BEGIN `SET ti.* = …`, can overwrite the contract's `ti.*` inputs, or
# shadow catalog functions through `search_path`, before the contract reads
# them; and schema/database-level privileges are not in the contract at all.
# Ops mitigated the TI-DELIVERY-001 window by applying exactly one reviewed
# reference, which contains no `ti.*`, no `set_config` and no `search_path`.
#
# This guard keeps the file inside that reviewed shape until Ops fixes MD1/md1
# — after which the reference may change again, with this guard updated in the
# same pull request. It is a lexical check against accidental drift, not a
# sandbox: the Ops contract remains the authority on the applied result.
#
# Refused (comments ignored):
#   * `set_config`, `search_path`, or any `ti.` setting name, anywhere;
#   * any DO block other than exactly one `DO $guard$ … $guard$;`, and inside it
#     any statement that writes or executes (SET, RESET, EXECUTE, PERFORM,
#     INSERT, UPDATE, DELETE, GRANT, REVOKE, ALTER, CREATE, DROP, TRUNCATE,
#     COPY, CALL): the guard may only read catalogs and raise;
#   * any SQL `SET`/`RESET` other than the three reviewed input lines
#     `SET testinbox.<api|ingestion|monitor>_role = :'<same>_role';`;
#   * any psql meta-command other than `\set ON_ERROR_STOP on` (an `\i`,
#     `\gexec` or `\!` would run text this guard never saw);
#   * block comments (`/* … */`), which would make the comment stripping above
#     ambiguous.
#
# Usage: scripts/check-roles-reference.sh [path]   (default: the repository's)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:-$REPO_ROOT/deploy/database/roles.reference.sql}"
[ -r "$FILE" ] || { echo "check-roles-reference: cannot read $FILE" >&2; exit 2; }

python3 - "$FILE" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
findings = []


def strip_comments(source):
    """Drop `--` comments outside single-quoted strings; keep strings and newlines."""
    out, i, in_string = [], 0, False
    while i < len(source):
        c = source[i]
        if in_string:
            out.append(c)
            if c == "'":
                if source[i + 1:i + 2] == "'":
                    out.append("'")
                    i += 1
                else:
                    in_string = False
        elif c == "'":
            in_string = True
            out.append(c)
        elif source.startswith("--", i):
            while i < len(source) and source[i] != "\n":
                i += 1
            continue
        elif source.startswith("/*", i):
            findings.append("a block comment (/* … */) — the guard cannot strip it unambiguously")
            out.append(c)
        else:
            out.append(c)
        i += 1
    return "".join(out)


def strip_strings(source):
    return re.sub(r"'(?:[^']|'')*'", "''", source)


def line_of(source, index):
    return source.count("\n", 0, index) + 1


code = strip_comments(text)

for pattern, what in [
    (r"set_config", "set_config"),
    (r"search_path", "search_path"),
    (r"\bti\.", "a ti.* setting name (the Ops grant contract's input namespace)"),
]:
    for match in re.finditer(pattern, code, re.IGNORECASE):
        findings.append(f"line {line_of(code, match.start())}: {what}")

do_blocks = list(re.finditer(r"\bDO\s+(\$[A-Za-z_0-9]*\$)(.*?)\1\s*;", code, re.IGNORECASE | re.DOTALL))
bare_do = [m for m in re.finditer(r"\bDO\b", strip_strings(code), re.IGNORECASE)]
if len(do_blocks) != 1 or do_blocks[0].group(1) != "$guard$":
    tags = ", ".join(b.group(1) for b in do_blocks) or "none"
    findings.append(f"expected exactly one DO $guard$ block, found {len(do_blocks)} ({tags})")
else:
    body = strip_strings(do_blocks[0].group(2))
    for match in re.finditer(
        r"\b(SET|RESET|EXECUTE|PERFORM|INSERT|UPDATE|DELETE|GRANT|REVOKE|ALTER|CREATE|DROP|TRUNCATE|COPY|CALL)\b",
        body,
        re.IGNORECASE,
    ):
        findings.append(
            f"line {line_of(code, do_blocks[0].start(2) + match.start())}: "
            f"{match.group(1).upper()} inside the DO $guard$ block (it may only read and raise)"
        )

top = code
for block in reversed(do_blocks):
    top = top[:block.start()] + "\n" * block.group(0).count("\n") + top[block.end():]
if len(bare_do) != len(do_blocks):
    findings.append(f"{len(bare_do)} DO keyword(s) but {len(do_blocks)} well-formed DO block(s)")

allowed_set = re.compile(r"^SET testinbox\.(api|ingestion|monitor)_role = :'\1_role';$")
seen_inputs = set()
for number, line in enumerate(top.split("\n"), start=1):
    stripped = line.strip()
    match = allowed_set.match(stripped)
    if match:
        seen_inputs.add(match.group(1))
        continue
    if stripped.startswith("\\"):
        if stripped != "\\set ON_ERROR_STOP on":
            findings.append(f"line {number}: psql meta-command {stripped.split()[0]} (only \\set ON_ERROR_STOP on is reviewed)")
        continue
    if "\\" in strip_strings(line):
        findings.append(f"line {number}: a psql meta-command after a statement (e.g. \\gexec) — only \\set ON_ERROR_STOP on is reviewed")
    for word in re.finditer(r"\b(SET|RESET)\b", strip_strings(line), re.IGNORECASE):
        findings.append(f"line {number}: {word.group(1).upper()} outside the three reviewed input lines")

if seen_inputs != {"api", "ingestion", "monitor"}:
    findings.append("the three reviewed SET testinbox.*_role input lines are not all present")

if findings:
    print(f"roles.reference.sql guard FAILED for {path} (MD1/md1: changes wait for the Ops contract fix):", file=sys.stderr)
    for finding in findings:
        print(f"  - {finding}", file=sys.stderr)
    sys.exit(1)
print(f"roles.reference.sql guard: {path} keeps the reviewed shape (one read-only DO $guard$, three input SETs, no set_config/search_path/ti.*)")
PY
