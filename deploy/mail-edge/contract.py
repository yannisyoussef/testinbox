#!/usr/bin/env python3
"""Reader for deploy/mail-edge/contract.yaml.

One implementation, shared by the renderer and the gate, so they can never
disagree about what the contract says.

It parses a deliberately restricted subset — comments, `key: value`, and one
level of two-space nesting — rather than depending on PyYAML, which is not
installed on every machine this has to run on (a developer mac, the CI runner,
and the edge container). A gate that cannot run is not a gate, and adding a
dependency to the thing that validates the deployment is the wrong trade.

Anything outside that subset is a parse error, not a silent misread: the file is
ours, and a manifest we cannot parse exactly must stop the build rather than
resolve to an empty map that makes every assertion vacuously pass.

Usage:
    contract.py get <dotted.key>      print one scalar
    contract.py keys <section>        print the keys of a section, one per line
    contract.py check                 parse and report the section names
"""

from __future__ import annotations

import sys
from pathlib import Path

DEFAULT_PATH = Path(__file__).resolve().parent / "contract.yaml"


class ContractError(RuntimeError):
    pass


def parse(text: str) -> dict:
    root: dict = {}
    section: dict | None = None
    for lineno, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent not in (0, 2):
            raise ContractError(f"line {lineno}: only 0- or 2-space indent is supported, got {indent}")
        if ":" not in raw:
            raise ContractError(f"line {lineno}: expected 'key: value'")
        key, _, value = raw.strip().partition(":")
        key = key.strip()
        value = value.strip()
        # A trailing comment is only a comment when it follows whitespace, so a
        # value like `127.0.0.0/8 [::1]/128` and a URL-ish value survive intact.
        if " #" in value:
            value = value.split(" #", 1)[0].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if indent == 0:
            if value == "":
                section = {}
                root[key] = section
            else:
                root[key] = value
                section = None
        else:
            if section is None:
                raise ContractError(f"line {lineno}: nested key '{key}' has no parent section")
            section[key] = value
    return root


def load(path: Path = DEFAULT_PATH) -> dict:
    try:
        return parse(path.read_text())
    except OSError as exc:
        raise ContractError(f"cannot read {path}: {exc}") from exc


def get(data: dict, dotted: str) -> str:
    node: object = data
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            raise ContractError(f"no such key: {dotted}")
        node = node[part]
    if isinstance(node, dict):
        raise ContractError(f"{dotted} is a section, not a scalar")
    return str(node)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        sys.stderr.write(__doc__)
        return 2
    command = argv[1]
    try:
        data = load()
        if command == "get" and len(argv) == 3:
            print(get(data, argv[2]))
        elif command == "keys" and len(argv) == 3:
            section = data.get(argv[2])
            if not isinstance(section, dict):
                raise ContractError(f"{argv[2]} is not a section")
            for key in section:
                print(key)
        elif command == "check":
            print(" ".join(k for k, v in data.items() if isinstance(v, dict)))
        else:
            sys.stderr.write(__doc__)
            return 2
    except ContractError as exc:
        print(f"contract: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
