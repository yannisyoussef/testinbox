#!/usr/bin/env bash
# Proves check-storage-enforcement-off.sh discriminates. The case it exists
# for is a committed TENANT_LIMITS/ALL — in a YAML value, in a `${VAR:-ALL}`
# default, or in the Kotlin property default — because any of them turns
# ADR-035 enforcement on at the next deploy with no §14 barrier evaluated.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-storage-enforcement-off.sh"
CLEAN="$SCRIPT_DIR/testdata/storage-activation/enforcement-off/clean"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# variant <name> → a copy of the clean tree at $TMP/<name>, to be mutated by the caller.
variant() { rm -rf "$TMP/$1"; cp -R "$CLEAN" "$TMP/$1"; }

check() {
    local name="$1" expected="$2" root="$3" must="${4:-}"
    local output status
    output="$(SCAN_ROOT="$root" "$GATE" 2>&1)"
    status=$?
    if [ "$status" != "$expected" ]; then
        echo "FAIL — $name (expected exit $expected, got $status)"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
        return
    fi
    if [ -n "$must" ] && ! printf '%s\n' "$output" | grep -qE -- "$must"; then
        echo "FAIL — $name (output does not match /$must/)"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
        return
    fi
    echo "ok   — $name"
    pass=$((pass + 1))
}

# The committed state: OFF everywhere, both property defaults OFF, prose free to say ALL.
check "the clean tree passes (comments, docs and OFF defaults are not findings)" 0 "$CLEAN" 'both property defaults are OFF'

# THE cases.
variant yaml
printf 'testinbox:\n  storage:\n    enforcement: TENANT_LIMITS\n' > "$TMP/yaml/backend/api/src/main/resources/application-deployed.yaml"
check "a YAML value of TENANT_LIMITS fails, naming the file" 1 "$TMP/yaml" 'application-deployed.yaml:3: storage enforcement is set to TENANT_LIMITS'

variant env-default
sed -i.bak 's/\${TESTINBOX_STORAGE_ENFORCEMENT:-OFF}/${TESTINBOX_STORAGE_ENFORCEMENT:-ALL}/' "$TMP/env-default/deploy/staging/compose.yaml" && rm -f "$TMP/env-default/deploy/staging/compose.yaml.bak"
check "a compose default of \${VAR:-ALL} fails (the environment decides nothing when unset)" 1 "$TMP/env-default" 'compose.yaml:[0-9]+: storage enforcement is set to \$\{TESTINBOX_STORAGE_ENFORCEMENT:-ALL\}'

variant spring-default
printf 'testinbox:\n  storage:\n    enforcement: ${TESTINBOX_STORAGE_ENFORCEMENT:ALL}\n' > "$TMP/spring-default/backend/ingestion/src/main/resources/application-deployed.yaml"
check "a Spring placeholder default of \${VAR:ALL} fails" 1 "$TMP/spring-default" 'storage enforcement is set to \$\{TESTINBOX_STORAGE_ENFORCEMENT:ALL\}'

variant property
sed -i.bak 's/StorageEnforcement = StorageEnforcement.OFF/StorageEnforcement = StorageEnforcement.ALL/' \
    "$TMP/property/backend/api/src/main/kotlin/email/testinbox/api/config/TestInboxProperties.kt" && rm -f "$TMP/property/backend/api/src/main/kotlin/email/testinbox/api/config/TestInboxProperties.kt.bak"
check "a Kotlin property default changed to ALL fails" 1 "$TMP/property" 'TestInboxProperties.kt: the enforcement default is not OFF'

variant property-missing
sed -i.bak '/enforcement: StorageEnforcement/d' "$TMP/property-missing/backend/ingestion/src/main/kotlin/email/testinbox/ingestion/config/IngestionProperties.kt" && rm -f "$TMP/property-missing/backend/ingestion/src/main/kotlin/email/testinbox/ingestion/config/IngestionProperties.kt.bak"
check "a property class with no declared default fails (absent is not OFF)" 1 "$TMP/property-missing" 'IngestionProperties.kt: declares no'

variant property-absent
rm "$TMP/property-absent/backend/api/src/main/kotlin/email/testinbox/api/config/TestInboxProperties.kt"
check "a missing property class fails rather than being skipped" 1 "$TMP/property-absent" 'property class not found'

variant env-example
printf 'TESTINBOX_STORAGE_ENFORCEMENT=ALL\n' >> "$TMP/env-example/deploy/staging/.env.example"
check "an env example that ships ALL fails" 1 "$TMP/env-example" '\.env\.example:[0-9]+: storage enforcement is set to ALL'

variant rehearsal
printf 'export TESTINBOX_STORAGE_ENFORCEMENT="tenant_limits"\n' >> "$TMP/rehearsal/scripts/staging-rehearsal.sh"
check "a quoted, lower-case value in the rehearsal fails (quotes and case do not launder it)" 1 "$TMP/rehearsal" 'staging-rehearsal.sh:[0-9]+: storage enforcement is set to'

# Spring's lenient enum binding: `tenant-limits`, `tenantlimits` and `Tenant Limits`
# all bind to TENANT_LIMITS, so each must fail the gate exactly like the canonical spelling.
variant lenient
printf 'TESTINBOX_STORAGE_ENFORCEMENT=tenant-limits\n' >> "$TMP/lenient/deploy/staging/.env.example"
check "a dashed lower-case spelling Spring would bind fails" 1 "$TMP/lenient" '.env.example:[0-9]+: storage enforcement is set to'
variant lenient2
printf 'testinbox:\n  storage:\n    enforcement: tenantlimits\n' > "$TMP/lenient2/backend/api/src/main/resources/application-deployed.yaml"
check "a squashed spelling Spring would bind fails" 1 "$TMP/lenient2" 'application-deployed.yaml:3: storage enforcement is set to'
variant lenient3
printf 'services:\n  api:\n    environment:\n      TESTINBOX_STORAGE_ENFORCEMENT: ${TESTINBOX_STORAGE_ENFORCEMENT:-Tenant.Limits}\n' > "$TMP/lenient3/deploy/staging/compose.yaml"
check "a dotted mixed-case compose default Spring would bind fails" 1 "$TMP/lenient3" 'compose.yaml:4: storage enforcement is set to'


variant workflow
printf '      - run: ./gradlew bootRun --args="--testinbox.storage.enforcement=ALL"\n' >> "$TMP/workflow/.github/workflows/ci.yml"
check "a workflow passing -Dtestinbox.storage.enforcement=ALL fails" 1 "$TMP/workflow" 'ci.yml:[0-9]+: storage enforcement is set to ALL'

variant nested-deploy
mkdir -p "$TMP/nested-deploy/deploy/production"
printf 'services:\n  api:\n    environment:\n      TESTINBOX_STORAGE_ENFORCEMENT: ALL\n' > "$TMP/nested-deploy/deploy/production/compose.yaml"
check "any compose file under deploy/ is a consumer" 1 "$TMP/nested-deploy" 'deploy/production/compose.yaml:4: storage enforcement is set to ALL'

# False-positive guards: prose may say anything; so may a comment in a consumer.
variant prose
printf '\n## Phase 4\n\nSet `TESTINBOX_STORAGE_ENFORCEMENT=ALL`.\n' >> "$TMP/prose/docs/adr/0035-fixture.md"
printf '# TESTINBOX_STORAGE_ENFORCEMENT=ALL   (set by Ops on the host, never here)\n' >> "$TMP/prose/deploy/staging/.env.example"
printf '    # enforcement: ALL\n' >> "$TMP/prose/backend/ingestion/src/main/resources/application.yaml"
check "prose and comments may name ALL" 0 "$TMP/prose"

variant other-key
printf 'testinbox:\n  storage:\n    enforcement-mode-label: ALL\n    enforcement: OFF\n' > "$TMP/other-key/backend/api/src/main/resources/application-deployed.yaml"
check "a different key that merely starts with 'enforcement' is not this setting" 0 "$TMP/other-key"

# The real repository: what CI gates. Reported, and asserted, because the gate
# step in ci.yml runs it against this tree.
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
check "this repository ships enforcement OFF" 0 "$REPO" 'both property defaults are OFF'

echo "----"
echo "check-storage-enforcement-off.test.sh: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
