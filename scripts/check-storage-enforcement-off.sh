#!/usr/bin/env bash
# ADR-035 §14 phase 2 / §18 gate 6: every committed deployed environment ships
# with storage enforcement OFF, and so does the code's own default. Likewise T1's
# lock protocol ships GLOBAL (mode a), in every consumer and in the default.
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

# Issue #102: the lab-only qualification record and the `lab` environment are
# never a committed value either. A consumer that sets TESTINBOX_ENVIRONMENT to
# lab, or gives the lab record path a non-empty default, would make a committed
# environment able to start an enforcing mode without a shipped record.
LAB_KEY_RE='(TESTINBOX_ENVIRONMENT|testinbox\.deployment\.environment|(^|[^A-Za-z0-9_.-])environment)[[:space:]]*[:=]'
LAB_PATH_RE='(TESTINBOX_STORAGE_QUALIFICATION_LAB_RECORD_PATH|lab-record-path|labRecordPath)[[:space:]]*[:=]'

# The literal a value carries: its `${VAR:-default}` / `${VAR:default}` default,
# nothing for a bare `${VAR}`, else the value itself; quotes stripped.
placeholder_literal() {
    local v="$1" literal
    v="$(printf '%s' "$v" | sed -e "s/^[[:space:]]*//" -e "s/^[\"']//" -e "s/[\"'][[:space:],]*$//")"
    case "$v" in
        \$\{*:-*\}) literal="${v#*:-}"; literal="${literal%\}}" ;;
        \$\{*:*\}) literal="${v#*:}"; literal="${literal%\}}" ;;
        \$\{*\}) literal="" ;;
        *) literal="$v" ;;
    esac
    printf '%s' "$literal" | sed -e "s/^[\"']//" -e "s/[\"']$//"
}

scan_values() {
    # scan_values <file> <key regex> → "lineno<TAB>value" per hit
    local file="$1" key="$2"
    strip_comments < "$file" | grep -nE "$key" 2>/dev/null | while IFS= read -r hit; do
        printf '%s\t%s\n' "${hit%%:*}" "$(printf '%s' "${hit#*:}" | grep -oE "$key[[:space:]]*$VALUE_RE" | head -1 | sed -E 's/^[^:=]*[:=][[:space:]]*//')"
    done
}

lab_findings="$(
    consumers | while IFS= read -r file; do
        [ -n "$file" ] || continue
        rel="${file#"$SCAN_ROOT"/}"
        scan_values "$file" "$LAB_KEY_RE" | while IFS="$(printf '\t')" read -r lineno value; do
            [ "$(placeholder_literal "$value" | tr '[:upper:]' '[:lower:]')" = "lab" ] &&
                printf '%s:%s: the environment is set to %s. The lab environment is never a committed value (issue #102).\n' "$rel" "$lineno" "$value"
        done
        scan_values "$file" "$LAB_PATH_RE" | while IFS="$(printf '\t')" read -r lineno value; do
            [ -n "$(placeholder_literal "$value")" ] &&
                printf '%s:%s: the lab qualification record path has a committed value (%s). It is set by Ops in the isolated lab only (issue #102).\n' "$rel" "$lineno" "$value"
        done
    done
)"
[ -z "$lab_findings" ] || { printf '%s\n' "$lab_findings" >&2; status=1; }

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

# T1's lock protocol (testinbox.storage.admission-locking): the share-partitioned
# T1 is a PROPOSED ADR-035 amendment, authorized for the lab only (owner,
# 2026-11-05), so no committed consumer may set anything but GLOBAL — in any
# spelling Spring's lenient enum binding accepts (`cohort-partitioned`,
# `CohortPartitioned`, `cohort.partitioned`...), and in any `${VAR:-default}` —
# and the ingestion property default is pinned to GLOBAL.
# The KEY, too, is matched the way Spring binds it: relaxed binding ignores case and
# drops '-', '_' and '.', so `admission-locking`, `admission_locking`,
# `admissionLocking`, `ADMISSIONLOCKING` and the env forms
# TESTINBOX_STORAGE_ADMISSION_LOCKING / TESTINBOX_STORAGE_ADMISSIONLOCKING are one key.
# locking_hits <file> → "lineno<TAB>value" for every assignment to that key.
locking_hits() {
    strip_comments < "$1" | grep -niE 'admission[-_.]?locking' 2>/dev/null | while IFS= read -r hit; do
        lineno="${hit%%:*}"
        # Anchored on the key itself, so a key inside a quoted argument (`--args="--testinbox...=x"`) is found.
        printf '%s\n' "${hit#*:}" | grep -oiE "[A-Za-z0-9_.-]*admission[-_.]?locking[[:space:]]*[:=][[:space:]]*$VALUE_RE" |
            while IFS= read -r token; do
            key="$(printf '%s' "$token" | sed -E 's/[[:space:]]*[:=].*$//')"
            # `(pattern)` form: bash 3.2 cannot parse a bare `pattern)` inside $( ).
            case "$(canonical_enum "$key")" in
                (admissionlocking | storageadmissionlocking | testinboxstorageadmissionlocking)
                    printf '%s\t%s\n' "$lineno" "$(printf '%s' "$token" | sed -E 's/^[^:=]*[:=][[:space:]]*//')" ;;
                (*) ;;
            esac
        done
    done
}
locking_findings="$(
    consumers | while IFS= read -r file; do
        [ -n "$file" ] || continue
        rel="${file#"$SCAN_ROOT"/}"
        locking_hits "$file" | while IFS="$(printf '\t')" read -r lineno value; do
            literal="$(canonical_enum "$(placeholder_literal "$value")")"
            case "$literal" in
                ("" | global) ;;
                (*) printf '%s:%s: T1 admission locking is set to %s. Only GLOBAL (ADR-035 mode a) may be committed; the share-partitioned T1 is a PROPOSED amendment for the lab only.\n' "$rel" "$lineno" "$value" ;;
            esac
        done
    done
)"
[ -z "$locking_findings" ] || { printf '%s\n' "$locking_findings" >&2; status=1; }

LOCKING_PROPERTY="backend/ingestion/src/main/kotlin/email/testinbox/ingestion/config/IngestionProperties.kt"
if [ -f "$SCAN_ROOT/$LOCKING_PROPERTY" ]; then
    decl="$(strip_comments < "$SCAN_ROOT/$LOCKING_PROPERTY" | grep -E 'admissionLocking:[[:space:]]*AdmissionLocking[[:space:]]*=' | head -1)"
    if [ -z "$decl" ]; then
        finding "$LOCKING_PROPERTY: declares no 'admissionLocking: AdmissionLocking = AdmissionLocking.GLOBAL' default"
    elif ! printf '%s' "$decl" | grep -qE '=[[:space:]]*AdmissionLocking\.GLOBAL([^A-Za-z0-9_]|$)'; then
        finding "$LOCKING_PROPERTY: the admission-locking default is not GLOBAL:$(printf '%s' "$decl" | sed 's/^[[:space:]]*/ /')"
    fi
fi

if [ "$status" -eq 0 ]; then
    echo "Storage enforcement: $scanned consumer file(s) scanned, no TENANT_LIMITS/ALL assignment; both property defaults are OFF; admission locking GLOBAL everywhere"
fi
exit "$status"
