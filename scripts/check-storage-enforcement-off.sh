#!/usr/bin/env bash
# ADR-035 §14 phase 2 / §18 gate 6: every committed deployed environment ships
# with storage enforcement OFF, and so does the code's own default.
#
# WHY THIS EXISTS: `testinbox.storage.enforcement` is configuration on the same
# digests. Nothing in the build, the image digest or the handoff can tell a
# Phase-2 (coexistence, nothing refused) deployment from a Phase-4 (enforcing)
# one — only this setting does. Phase 4 is reached by Ops, after the activation
# barrier of §14 is PROVEN (scripts/check-storage-activation.sh), never by a
# value committed to this repository: a committed TENANT_LIMITS or ALL would
# turn enforcement on at the next deploy of whatever environment reads it,
# with no barrier evaluated. The same holds for the Kotlin property default:
# a binary whose default is not OFF enforces wherever the setting is absent.
#
# What is scanned: only files a deployed environment actually CONSUMES (Spring
# application YAML, compose files, the env example, the rehearsal, the
# workflows) and the two property classes. Prose and ADRs are exempt: they must
# be free to discuss `enforcement=ALL`.
#
# Usage: scripts/check-storage-enforcement-off.sh
# Fixtures: SCAN_ROOT=<dir> to check a tree other than this repository.
# Exit: 0 clean, 1 finding(s)
set -uo pipefail

SCAN_ROOT="${SCAN_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

PROPERTY_FILES="backend/api/src/main/kotlin/email/testinbox/api/config/TestInboxProperties.kt
backend/ingestion/src/main/kotlin/email/testinbox/ingestion/config/IngestionProperties.kt"

status=0
finding() { printf '%s\n' "$*" >&2; status=1; }

# The consumers. Fixed paths first, then every compose/manifest under deploy/.
consumers() {
    {
        for f in "$SCAN_ROOT"/backend/api/src/main/resources/application*.yaml \
            "$SCAN_ROOT"/backend/ingestion/src/main/resources/application*.yaml \
            "$SCAN_ROOT"/deploy/staging/.env.example \
            "$SCAN_ROOT"/docker-compose.yml \
            "$SCAN_ROOT"/scripts/staging-rehearsal.sh \
            "$SCAN_ROOT"/.github/workflows/*.yml; do
            [ -f "$f" ] && printf '%s\n' "$f"
        done
        find "$SCAN_ROOT/deploy" \( -name '*.yaml' -o -name '*.yml' \) -type f -print 2>/dev/null
    } | sort -u
}

# Comments are not configuration: whole-line `#`, trailing ` #`, and `//`.
strip_comments() { sed -e 's/^[[:space:]]*#.*$//' -e 's/[[:space:]]#.*$//' -e 's#//.*$##'; }

# The setting, however spelled: the bare YAML key (guarded so `max-enforcement:`
# or `enforcement-mode:` are not it), the dotted Spring property, or the
# environment variable. The value is the first token after `:`/`=`: a quoted
# string, a `${VAR:-default}` placeholder, or a bare word.
KEY_RE='((^|[^A-Za-z0-9_.-])enforcement|testinbox\.storage\.enforcement|TESTINBOX_STORAGE_ENFORCEMENT)[[:space:]]*[:=]'
VALUE_RE='("[^"]*"|'"'"'[^'"'"']*'"'"'|\$\{[^}]*\}|[^[:space:]"'"'"']+)'

# An assignment enables enforcement when its value, or its `${VAR:-default}`
# default, is TENANT_LIMITS or ALL. Quotes and case do not change that.
# Spring binds an enum LENIENTLY: it folds case and drops every non-alphanumeric
# character before comparing, so `tenant-limits`, `tenantlimits`, `Tenant Limits`
# and `tenant.limits` all bind to TENANT_LIMITS. The gate canonicalises the same
# way, or a spelling Spring accepts would launder a committed switch-on.
canonical_enum() {
    printf '%s' "$1" | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]'
}

enforcing_value() {
    local v="$1" literal
    v="$(printf '%s' "$v" | sed -e "s/^[[:space:]]*//" -e "s/^[\"']//" -e "s/[\"'][[:space:],]*$//")"
    case "$v" in
        \$\{*:-*\}) literal="${v#*:-}"; literal="${literal%\}}" ;;
        \$\{*:*\}) literal="${v#*:}"; literal="${literal%\}}" ;;
        *) literal="$v" ;;
    esac
    literal="$(printf '%s' "$literal" | sed -e "s/^[\"']//" -e "s/[\"']$//")"
    case "$(canonical_enum "$literal")" in
        tenantlimits|all) return 0 ;;
    esac
    return 1
}

scanned="$(consumers | grep -c . 2>/dev/null; true)"
# The scan runs in a pipeline, so its findings are collected into a variable.
findings="$(
    consumers | while IFS= read -r file; do
        [ -n "$file" ] || continue
        rel="${file#"$SCAN_ROOT"/}"
        strip_comments < "$file" |
            grep -nE "$KEY_RE" 2>/dev/null |
            while IFS= read -r hit; do
                lineno="${hit%%:*}"
                token="$(printf '%s' "${hit#*:}" | grep -oE "$KEY_RE[[:space:]]*$VALUE_RE" | head -1)"
                value="$(printf '%s' "$token" | sed -E 's/^[^:=]*[:=][[:space:]]*//')"
                enforcing_value "$value" &&
                    printf '%s:%s: storage enforcement is set to %s. Enforcement is turned on by Ops after the ADR-035 §14 barrier is proven, never by a committed environment.\n' "$rel" "$lineno" "$value"
            done
    done
)"
[ -z "$findings" ] || { printf '%s\n' "$findings" >&2; status=1; }

# The code default: `enforcement: StorageEnforcement = StorageEnforcement.OFF`
# in both deployables' property classes. Absent is a finding — a binary with no
# declared default has no proven default.
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    file="$SCAN_ROOT/$rel"
    if [ ! -f "$file" ]; then
        finding "$rel: property class not found; the enforcement default cannot be verified"
        continue
    fi
    decl="$(strip_comments < "$file" | grep -E 'enforcement:[[:space:]]*StorageEnforcement[[:space:]]*=' | head -1)"
    if [ -z "$decl" ]; then
        finding "$rel: declares no 'enforcement: StorageEnforcement = StorageEnforcement.OFF' default (ADR-035 §14 phase 2: binaries ship OFF)"
    elif ! printf '%s' "$decl" | grep -qE '=[[:space:]]*StorageEnforcement\.OFF([^A-Za-z0-9_]|$)'; then
        finding "$rel: the enforcement default is not OFF:$(printf '%s' "$decl" | sed 's/^[[:space:]]*/ /')"
    fi
done <<EOF
$PROPERTY_FILES
EOF

if [ "$status" -eq 0 ]; then
    echo "Storage enforcement: $scanned consumer file(s) scanned, no TENANT_LIMITS/ALL assignment; both property defaults are OFF"
fi
exit "$status"
