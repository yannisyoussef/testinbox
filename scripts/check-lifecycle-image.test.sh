#!/usr/bin/env bash
# Self-test for check-lifecycle-image.sh with a stub `docker` (no daemon needed).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-lifecycle-image.sh"
SOURCE="$SCRIPT_DIR/../backend/api/src/main/kotlin/email/testinbox/api/AccountLifecycleCommand.kt"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0
ok() { echo "ok   — $1"; pass=$((pass + 1)); }
bad() { echo "FAIL — $1"; fail=$((fail + 1)); }

MARKER="$(sed -n 's/^MARKER="\(.*\)"$/\1/p' "$GATE")"
grep -qF "\"$MARKER\"" "$SOURCE" && ok "the script's marker is the command's USAGE_MARKER" \
    || bad "the script's marker differs from AccountLifecycleCommand.USAGE_MARKER"

# stub <name> <exit> <stdout+stderr text>: a fake docker that records its argv.
stub() {
    cat > "$TMP/$1" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$@" > "$TMP/$1.argv"
printf '%b' "$3"
exit $2
STUB
    chmod +x "$TMP/$1"
}
expect() { # expect <name> <expected-exit> <stub>
    DOCKER="$TMP/$3" "$GATE" "registry.example/testinbox-api@sha256:fixture" >/dev/null 2>&1; local rc=$?
    [ "$rc" = "$2" ] && ok "$1" || bad "$1 (expected $2, got $rc)"
}
stub good 2 "$MARKER\nusage: account-lifecycle ...\n"
expect "exit 2 with the marker passes" 0 good
grep -qx -- "--network" "$TMP/good.argv" && grep -qx none "$TMP/good.argv" && ok "it runs with --network none" || bad "no --network none"
grep -qx "JAVA_TOOL_OPTIONS=" "$TMP/good.argv" && ok "it clears JAVA_TOOL_OPTIONS" || bad "JAVA_TOOL_OPTIONS not cleared"
stub picked 2 "Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75\n$MARKER\nusage: ...\n"
expect "a JVM notice before the marker still passes (the marker is matched as a whole line)" 0 picked
stub old 0 "  .   ____          _\nStarted ApiApplication\n"
expect "an older image that boots the API (exit 0) fails" 1 old
stub nomarker 2 "usage: account-lifecycle suspend|resume|delete|status <account-uuid>\n"
expect "exit 2 without the marker (a pre-002b usage) fails" 1 nomarker
stub partial 2 "$MARKER,extra\n"
expect "a marker with trailing text is not the marker" 1 partial
stub wrongrc 1 "$MARKER\n"
expect "the marker with exit 1 fails" 1 wrongrc
"$GATE" >/dev/null 2>&1; [ $? = 2 ] && ok "no image reference is a usage error" || bad "missing argument not a usage error"

echo "check-lifecycle-image.test.sh: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
