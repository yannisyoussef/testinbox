#!/usr/bin/env bash
# Tests for scan-images.sh — the environment-sensitive vulnerability policy.
#
# The policy is a decision recorded in an ADR, and until it was extracted from
# the workflow nothing could prove either half of it. In particular nothing
# proved the BLOCKING half can actually fail, and it only ever runs on release
# PRs, where a broken step and a real finding look identical.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCAN="$SCRIPT_DIR/scan-images.sh"

pass=0
fail=0
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

# A `trivy` that records its arguments and exits however the case wants.
cat >"$STUB_DIR/trivy" <<'STUB'
#!/usr/bin/env bash
[[ "${1:-}" == "--version" ]] && { echo "Version: stub"; exit 0; }
echo "$*" >> "$TRIVY_STUB_ARGS"
exit "${TRIVY_STUB_EXIT:-0}"
STUB
chmod +x "$STUB_DIR/trivy"

IMAGES=(
  "ghcr.io/o/testinbox-api@sha256:aaa"
  "ghcr.io/o/testinbox-ingestion@sha256:bbb"
  "ghcr.io/o/testinbox-migrator@sha256:ccc"
  "ghcr.io/o/testinbox-web@sha256:ddd"
)

run_scan() {
  : >"$STUB_DIR/args"
  TRIVY_STUB_ARGS="$STUB_DIR/args" TRIVY_STUB_EXIT="$1" ENFORCE="$2" TRIVY_BIN="$STUB_DIR/trivy" \
    "$SCAN" "${IMAGES[@]}" >"$STUB_DIR/out" 2>&1
  echo $?
}

record() {
  if [[ "$2" == "ok" ]]; then echo "ok   — $1"; pass=$((pass + 1))
  else echo "FAIL — $1"; fail=$((fail + 1)); fi
}

# --- develop / pull request: informational -----------------------------------
record "a clean scan passes in informational mode" \
  "$([[ "$(run_scan 0 false)" == 0 ]] && echo ok || echo no)"
record "FINDINGS DO NOT BLOCK develop — this is the noisy-gate protection" \
  "$([[ "$(run_scan 1 false)" == 0 ]] && echo ok || echo no)"

# --- promotion to master: blocking -------------------------------------------
record "a clean scan passes on the promotion path" \
  "$([[ "$(run_scan 0 true)" == 0 ]] && echo ok || echo no)"
record "FINDINGS BLOCK promotion — the gate can actually fail" \
  "$([[ "$(run_scan 1 true)" != 0 ]] && echo ok || echo no)"

# --- the flags that define the policy ----------------------------------------
run_scan 0 true >/dev/null
record "--ignore-unfixed is passed on the blocking path (an unfixed CVE must not block a release)" \
  "$(grep -q -- '--ignore-unfixed' "$STUB_DIR/args" && echo ok || echo no)"
record "severity is limited to HIGH,CRITICAL" \
  "$(grep -q -- '--severity HIGH,CRITICAL' "$STUB_DIR/args" && echo ok || echo no)"
record "the blocking path asks trivy itself for a non-zero exit" \
  "$(grep -q -- '--exit-code 1' "$STUB_DIR/args" && echo ok || echo no)"

run_scan 0 false >/dev/null
record "--ignore-unfixed is passed on the informational path too" \
  "$(grep -q -- '--ignore-unfixed' "$STUB_DIR/args" && echo ok || echo no)"
record "the informational path asks trivy for exit 0" \
  "$(grep -q -- '--exit-code 0' "$STUB_DIR/args" && echo ok || echo no)"

# --- every image, every time --------------------------------------------------
run_scan 0 true >/dev/null
scanned=$(wc -l < "$STUB_DIR/args" | tr -d ' ')
record "all four images are scanned, so a typo'd loop cannot skip one silently" \
  "$([[ "$scanned" == "4" ]] && echo ok || echo no)"
record "images are addressed by digest, not tag" \
  "$(! grep -qE 'testinbox-[a-z]+:' "$STUB_DIR/args" && echo ok || echo no)"

# --- a scanner that did not install must not pass quietly ---------------------
missing_status=$(TRIVY_BIN="$STUB_DIR/definitely-not-here" ENFORCE=false "$SCAN" "${IMAGES[@]}" >/dev/null 2>&1; echo $?)
record "an unrunnable scanner fails even in informational mode" \
  "$([[ "$missing_status" != "0" ]] && echo ok || echo no)"

record "no arguments is a usage error" \
  "$(ENFORCE=false TRIVY_BIN="$STUB_DIR/trivy" "$SCAN" >/dev/null 2>&1; [[ $? == 2 ]] && echo ok || echo no)"

echo "----"
echo "scan-images.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
