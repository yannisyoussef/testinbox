#!/usr/bin/env bash
# The wait must be able to refuse: a failed build, a build that never comes,
# and a head that can never have one.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWAIT="$SCRIPT_DIR/await-candidate-build.sh"
pass=0; fail=0
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
SHA="$(printf 'e%.0s' {1..40})"

# gh stub: each call consumes the next line of the fixture and prints it;
# the last line repeats. Requires the flags the real query must carry.
cat >"$WORK/gh" <<'STUB'
#!/usr/bin/env bash
[[ " $* " == *" --event push "* && " $* " == *" --commit "* && " $* " == *" --workflow "* ]] || { echo "bad query: $*" >&2; exit 99; }
n=$(cat "$GH_CALLS" 2>/dev/null || echo 0); echo $((n + 1)) > "$GH_CALLS"
line=$(sed -n "$((n + 1))p" "$GH_SEQUENCE"); [[ -n "$line" ]] || line=$(tail -1 "$GH_SEQUENCE")
printf '%s\n' "$line"
STUB
chmod +x "$WORK/gh"

run() {
  : > "$WORK/calls"
  GH_BIN="$WORK/gh" GH_CALLS="$WORK/calls" GH_SEQUENCE="$WORK/seq" GITHUB_REPOSITORY="testowner/testinbox" \
    "$AWAIT" "$@" >"$WORK/out" 2>&1
  echo $?
}
check() {
  local name="$1" expected="$2"; shift 2
  local status; status=$(run "$@")
  if [[ "$status" == "$expected" ]]; then echo "ok   — $name"; pass=$((pass + 1)); else echo "FAIL — $name (expected $expected, got $status)"; sed 's/^/       /' "$WORK/out" | tail -3; fail=$((fail + 1)); fi
}

printf '%s\n' '[{"databaseId":1,"status":"completed","conclusion":"success"}]' > "$WORK/seq"
check "a completed successful build returns immediately" 0 --candidate "$SHA" --head-ref develop --timeout-seconds 5 --interval-seconds 1
printf '%s\n' '[]' '[{"databaseId":1,"status":"in_progress","conclusion":null}]' '[{"databaseId":1,"status":"completed","conclusion":"success"}]' > "$WORK/seq"
check "a build that appears and then completes is waited for" 0 --candidate "$SHA" --head-ref develop --timeout-seconds 30 --interval-seconds 1
[[ "$(cat "$WORK/calls")" == 3 ]] && { echo "ok   — and it polled exactly until completion (3 calls)"; pass=$((pass + 1)); } || { echo "FAIL — polled $(cat "$WORK/calls") times"; fail=$((fail + 1)); }
printf '%s\n' '[{"databaseId":1,"status":"completed","conclusion":"failure"}]' > "$WORK/seq"
check "a failed develop build is a refusal, not a wait" 1 --candidate "$SHA" --head-ref develop --timeout-seconds 30 --interval-seconds 1
printf '%s\n' '[{"databaseId":1,"status":"completed","conclusion":"cancelled"}]' > "$WORK/seq"
check "a cancelled build is a refusal" 1 --candidate "$SHA" --head-ref develop --timeout-seconds 30 --interval-seconds 1
printf '%s\n' '[]' > "$WORK/seq"
check "a build that never appears refuses at the deadline" 1 --candidate "$SHA" --head-ref develop --timeout-seconds 2 --interval-seconds 1
printf '%s\n' '[{"databaseId":1,"status":"in_progress","conclusion":null}]' > "$WORK/seq"
check "a build still running at the deadline refuses" 1 --candidate "$SHA" --head-ref develop --timeout-seconds 2 --interval-seconds 1
printf '%s\n' '[{"databaseId":1,"status":"completed","conclusion":"success"}]' > "$WORK/seq"
check "a feature-branch head is refused before any wait" 1 --candidate "$SHA" --head-ref feat/x --timeout-seconds 30 --interval-seconds 1
[[ "$(cat "$WORK/calls")" == "" || "$(cat "$WORK/calls")" == 0 ]] && { echo "ok   — and nothing was queried for it"; pass=$((pass + 1)); } || { echo "FAIL — queried $(cat "$WORK/calls") times for a feature head"; fail=$((fail + 1)); }
check "a malformed candidate is refused" 1 --candidate abc --head-ref develop
echo "----"; echo "await-candidate-build.test.sh: $pass passed, $fail failed"; (( fail == 0 ))
