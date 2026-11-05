#!/usr/bin/env bash
# Proves scripts/build-qualification-record.py discriminates. On a FAKE bundle (plainly fake values,
# scripts/testdata/qualification-record/make-fake-bundle.py):
#   - a complete bundle builds a record that is byte-identical to the golden file the Kotlin parser test
#     (GeneratedQualificationRecordTest) loads through QualificationRecords.parse, and that the REAL gate Q and
#     gate F of check-storage-activation.sh pass with --environment production;
#   - a missing file, a SHA mismatch, a non-PASS experiment or sweep, a disagreeing identity element and a
#     recordId that differs from gate F's evidence each REFUSE (exit 1) and write nothing;
#   - E7 carried is accepted only with an amendment present in the ADR text (W3 A5).
# And the §9a evaluator, run on the committed staging corpus, reproduces the shipped staging record.
# No network, no database, no Docker. UPDATE_GOLDEN=1 rewrites the golden record (review the diff).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
GEN="$SCRIPT_DIR/build-qualification-record.py"
MAKE="$SCRIPT_DIR/testdata/qualification-record/make-fake-bundle.py"
GATE="$SCRIPT_DIR/check-storage-activation.sh"
GOLDEN="$REPO/backend/storage/src/test/resources/adr035-generated/fake-production-record.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { echo "ok   — $1"; pass=$((pass + 1)); }
bad() { echo "FAIL — $1"; shift; [ $# -eq 0 ] || printf '%s\n' "$@" | sed 's/^/       /'; fail=$((fail + 1)); }

# resum <bundle>: rewrite SHA256SUMS after a deliberate change (portable: python, not sha256sum/shasum).
resum() {
    python3 - "$1" <<'EOF'
import hashlib, os, sys
root = sys.argv[1]
with open(os.path.join(root, "SHA256SUMS"), "w") as f:
    for dp, _, files in sorted(os.walk(root)):
        for fn in sorted(files):
            rel = os.path.relpath(os.path.join(dp, fn), root)
            if rel != "SHA256SUMS":
                f.write(f"{hashlib.sha256(open(os.path.join(dp, fn), 'rb').read()).hexdigest()}  {rel}\n")
EOF
}
# fresh <name> [--e7 carried]: a new fake bundle under $TMP/<name>
fresh() { local n="$1"; shift; python3 "$MAKE" "$TMP/$n" "$@"; }
# gen <name> [extra args]: run the generator on $TMP/<name>; OUT, STATUS set; output root $TMP/<name>/out
OUT=""; STATUS=0
gen() {
    local n="$1"; shift
    mkdir -p "$TMP/$n/out"
    OUT="$(python3 "$GEN" --bundle "$TMP/$n/bundle" --expected "$TMP/$n/expected.json" --repo "$TMP/$n/repo" \
        --out-root "$TMP/$n/out" "$@" 2>&1)"; STATUS=$?
}
# refused <case> <name> <regex>: exit 1, the regex in the output, and no record directory written
refused() {
    local c="$1" n="$2" re="$3"
    gen "$n"
    if [ "$STATUS" != 1 ]; then bad "$c (expected exit 1, got $STATUS)" "$OUT"; return; fi
    if ! printf '%s\n' "$OUT" | grep -qE -- "$re"; then bad "$c (output does not match /$re/)" "$OUT"; return; fi
    if [ -n "$(ls -A "$TMP/$n/out")" ]; then bad "$c (a refused build wrote $(ls "$TMP/$n/out"))"; return; fi
    ok "$c"
}
# edit <name> <rel> <python expression over d>: change one JSON file of the bundle, then resum
edit() {
    python3 - "$TMP/$1/bundle/$2" "$3" <<'EOF'
import json, sys
p, expr = sys.argv[1], sys.argv[2]
d = json.load(open(p))
exec(expr)
json.dump(d, open(p, "w"), indent=2)
EOF
    resum "$TMP/$1/bundle"
}

# ---------------------------------------------------------------------------------- the complete bundle
fresh good
gen good
REC="$TMP/good/out/production-2000-01-01/record.json"
if [ "$STATUS" = 0 ] && [ -f "$REC" ]; then ok "a complete fake bundle builds a record"; else bad "a complete fake bundle builds a record (exit $STATUS)" "$OUT"; fi
if [ "${UPDATE_GOLDEN:-0}" = 1 ]; then mkdir -p "$(dirname "$GOLDEN")"; cp "$REC" "$GOLDEN"; echo "golden rewritten: $GOLDEN"; fi
if cmp -s "$REC" "$GOLDEN"; then ok "the record is byte-identical to the golden file the Kotlin parser test loads"
else bad "the record differs from $GOLDEN (UPDATE_GOLDEN=1 after review)" "$(diff "$GOLDEN" "$REC" | head -20)"; fi
D="$TMP/good/out/production-2000-01-01"
if [ -f "$D/PROVENANCE.md" ] && [ -f "$D/evidence/SHA256SUMS" ] && cmp -s "$D/evidence/MANIFEST.json" "$TMP/good/bundle/MANIFEST.json"; then
    ok "PROVENANCE.md written and the raw evidence copied byte for byte"
else bad "PROVENANCE.md / evidence copy"; fi
if (cd "$D" && python3 -c "
import hashlib,sys
for l in open('SHA256SUMS'):
    h,n=l.split(None,1); n=n.strip()
    sys.exit(1) if hashlib.sha256(open(n,'rb').read()).hexdigest()!=h else None
"); then ok "the output's SHA256SUMS verifies record.json and PROVENANCE.md"; else bad "output SHA256SUMS"; fi
for l in PROD-QUAL PROD-NOW DERIVED CODE; do
    grep -q "| $l |" "$D/PROVENANCE.md" || bad "PROVENANCE.md has no $l row"
done
if grep -qE '\| (LAB|POST-REBOOT) \|' "$D/PROVENANCE.md"; then bad "PROVENANCE.md carries a LAB or POST-REBOOT member"; else ok "PROVENANCE.md labels PROD-QUAL/PROD-NOW/DERIVED/CODE, no LAB or POST-REBOOT"; fi
gen good
if [ "$STATUS" = 1 ] && printf '%s' "$OUT" | grep -q "already exists"; then ok "an existing record directory is never overwritten"; else bad "overwrite refused (exit $STATUS)" "$OUT"; fi
mkdir -p "$TMP/good/out2"
OUT="$(python3 "$GEN" --bundle "$TMP/good/bundle" --expected "$TMP/good/expected.json" --repo "$TMP/good/repo" --out-root "$TMP/good/out2" --check 2>&1)"
if [ $? = 0 ] && [ -z "$(ls -A "$TMP/good/out2")" ]; then ok "--check verifies and writes nothing"; else bad "--check" "$OUT"; fi

# ---------------------------------------------------- the REAL gate Q and gate F, --environment production
Q="$TMP/qual"; mkdir -p "$Q"
RID="$(jq -r .recordId "$REC")"
cp "$REC" "$Q/$RID.json"; printf '%s.json\n' "$RID" > "$Q/index.txt"
jq '{minio: (.minio | {imageIndexDigest, platformMemberDigest, release, commitId, mode, driveCount, timeoutEnvironment,
      timeoutCliFlags, runtimeConfig: {hash: .runtimeConfig.hash}, storageClassInlineDefaults}),
     host: (.host | {kernelRelease, filesystemType, mountOptions}), network: (.network | {directPath, proxy}),
     uploadImplementationVersion}' "$REC" > "$TMP/identity.json"
# The gate run's clock is the fixture state's; only collectedAt moves into its A_obs window.
jq '.collectedAt = "2026-10-07T00:00:00Z"' "$TMP/good/bundle/gate-f/filesystem-evidence.json" > "$TMP/evidence.json"
GOUT="$("$GATE" --mode ALL --environment production --repo "$REPO" --qualification-dir "$Q" \
    --backend-identity "$TMP/identity.json" --qualification-valid-metric 1 \
    --filesystem-evidence "$TMP/evidence.json" \
    --footprint-state "$SCRIPT_DIR/testdata/storage-activation/filesystem/state-good.json" 2>&1)"
if printf '%s\n' "$GOUT" | grep -qE "^Q-qualification[[:space:]]+PASS[[:space:]]+identity equals record $RID.json \(eligible\)"; then
    ok "gate Q matches the generated record and re-derives it eligible"
else bad "gate Q on the generated record" "$(printf '%s\n' "$GOUT" | grep -E '^(Q|F)-')"; fi
if printf '%s\n' "$GOUT" | grep -qE '^F-filesystem[[:space:]]+PASS'; then
    ok "gate F (--environment production) passes on the generated record and the bundle's gate F evidence"
else bad "gate F on the generated record" "$(printf '%s\n' "$GOUT" | grep -E '^F-')"; fi

# ------------------------------------------------------------------------------------------ refusals
fresh nosums; rm "$TMP/nosums/bundle/SHA256SUMS"
refused "no SHA256SUMS → refuse before reading anything" nosums "SHA256SUMS is missing"
fresh sha; printf 'tampered\n' >> "$TMP/sha/bundle/experiments/E4/run.log"
refused "a SHA mismatch → refuse" sha "SHA256 mismatch: experiments/E4/run.log"
fresh gone; rm "$TMP/gone/bundle/experiments/E4.json"
refused "a listed file that is absent → refuse" gone "SHA256SUMS lists experiments/E4.json, which is not in the bundle"
fresh missing; rm "$TMP/missing/bundle/experiments/E4.json"; resum "$TMP/missing/bundle"
refused "a missing required file (re-summed) → refuse" missing "required file experiments/E4.json is missing"
fresh missing2; rm "$TMP/missing2/bundle/identity/minio-imagetools-raw.json"; resum "$TMP/missing2/bundle"
refused "no imagetools raw → imageIndexDigest cannot be resolved → refuse" missing2 "minio.imageIndexDigest"
fresh extra; printf 'x\n' > "$TMP/extra/bundle/unlisted.txt"
refused "a file not in SHA256SUMS → refuse" extra "unlisted.txt is in the bundle but not in SHA256SUMS"
fresh e9; edit e9 experiments/E9.json 'd["verdict"] = "FAIL"'
refused "a non-PASS experiment → refuse" e9 "E9 is not PASS"
fresh e2; edit e2 experiments/E2.json 'd["measurements"]["worstObjectOverheadBytes"] = 28000'
refused "a measured overhead above O_max → refuse" e2 "E2: worst per-object overhead 28000 exceeds the O_max 24576"
fresh late; python3 - "$TMP/late/bundle/qualification-9a/main/results-slow.jsonl" <<'EOF'
import sys; p = sys.argv[1]; open(p, "a").write('{"scenario": "freeze45-W", "key": "late/0", "committed_after_thaw": true, "witness_during_freeze": "blocked"}\n')
EOF
resum "$TMP/late/bundle"
refused "a §9a late commit (recomputed from raw) → refuse" late "§9a sweep is not PASS: 1 late commit"
fresh kern; edit kern identity/qualification-check-observed.json 'd["observed"]["host.kernelRelease"] = "0.0.1-other"'
refused "identity elements disagree (qualification-check kernel ≠ baseline) → refuse" kern "identity element host.kernelRelease"
fresh member; edit member identity/minio-image-inspect.json 'd[0]["RepoDigests"] = ["fake.invalid/minio@sha256:" + "e" * 64]'
refused "the running member is not the index's amd64 member → refuse" member "the index lists linux/amd64"
fresh uuid; edit uuid experiments/E3.json 'd["filesystemUuid"] = "11111111-0000-4000-8000-000000000000"'
refused "an experiment on another filesystem → refuse" uuid "experiments/E3.json: filesystemUuid"
fresh e7scope; edit e7scope experiments/E7.json 'd["measurements"]["metadataInodesScope"] = "minio-sys+bucket-directories"'
refused "an E7 figure counted over another scope than .minio.sys → refuse" e7scope "measurements.metadataInodesScope is 'minio-sys\\+bucket-directories'"
fresh fescope; edit fescope gate-f/filesystem-evidence.json 'del(d["minio"]["metadataInodesScope"])'
refused "gate F evidence from a collector that does not count .minio.sys only → refuse" fescope "gate F evidence minio.metadataInodesScope is None"
fresh rid; edit rid gate-f/filesystem-evidence.json 'd["qualificationRecordId"] = "production-amd64-fake-host-0-1999-12-31"'
refused "recordId ≠ gate F evidence qualificationRecordId → refuse" rid "gate F evidence qualificationRecordId .* ≠ recordId"
fresh art; edit art experiments/E10.json 'd["artifact"] = None'
refused "an app-level experiment that names no candidate artifact → refuse" art "experiments/E10.json: artifact"
fresh early; edit early experiments/E5.json 'd["startedAt"] = "1999-12-31T23:00:00Z"'
refused "an experiment before the post-reboot baseline → refuse" early "E5 started 1999-12-31T23:00:00Z, before the post-reboot baseline"
fresh e7lab; edit e7lab experiments/E7.json 'd["venue"] = "carried"'
refused "E7 carried without MANIFEST e7.mode carried → refuse" e7lab "experiments/E7.json: venue is 'carried', not final-filesystem"
# One metadata-bytes definition (scope minio-sys+unattributed) for E2, E7, the gate F evidence and the declared M.
fresh e2bytes; edit e2bytes experiments/E2.json 'd["measurements"]["metadataBytesScope"] = "minio-sys+bucket-directories"'
refused "an E2 bytes figure counting every bucket directory → refuse" e2bytes "experiments/E2.json measurements.metadataBytesScope is 'minio-sys\\+bucket-directories'"
fresh e7bytes; edit e7bytes experiments/E7.json 'del(d["measurements"]["metadataBytesMeasured"])'
refused "an E7 without its metadata bytes peak → refuse" e7bytes "experiments/E7.json measurements: 'metadataBytesMeasured' is missing"
fresh febytes; edit febytes gate-f/filesystem-evidence.json 'del(d["minio"]["metadataBytesScope"])'
refused "gate F evidence from a collector that does not count unattributed allocations only → refuse" febytes "gate F evidence minio.metadataBytesScope is None"
fresh mbudget; edit mbudget gate-f/filesystem-evidence.json 'd["declared"]["metadataBudgetBytes"] = 50000000'
refused "a declared M below the E2/E7 metadata bytes measurement → refuse" mbudget "declares M = 50000000, below the E2/E7 metadata bytes measurement 52428800"
fresh nobucket; edit nobucket gate-f/filesystem-evidence.json 'd["minio"]["bucketPresent"] = False'
refused "gate F evidence collected with no bucket directory → refuse" nobucket "gate F evidence minio.bucketPresent is not true"
fresh excl; edit excl experiments/E7.json 'd["measurements"]["metadataInodesScope"] = "minio-sys-excl-trash"'
refused "an E7 inode figure without .trash, with no armed inode-headroom latch → refuse" excl "minio-sys-excl-trash is used but gate F evidence monitor.inodeHeadroomLatchArmed is not true"
fresh exclok; edit exclok experiments/E7.json 'd["measurements"]["metadataInodesScope"] = "minio-sys-excl-trash"'
edit exclok gate-f/filesystem-evidence.json 'd["minio"]["metadataInodesScope"] = "minio-sys-excl-trash"; d["monitor"] = {"inodeHeadroomLatchArmed": True}'
gen exclok
if [ "$STATUS" = 0 ] && [ "$(jq -r .filesystem.metadataInodesScope "$TMP/exclok/out/production-2000-01-01/record.json")" = minio-sys-excl-trash ]; then
    ok "an E7 inode figure without .trash builds when the inode-headroom latch is armed, and the record states that scope"
else bad "excl-trash with armed latch (exit $STATUS)" "$OUT"; fi
if [ "$(jq -c '[.filesystem.metadataBytesMeasured, .filesystem.metadataBytesScope]' "$REC")" = '[52428800,"minio-sys+unattributed"]' ]; then
    ok "the record carries the larger of E2 and E7 as metadataBytesMeasured, scope minio-sys+unattributed"
else bad "record metadataBytes members" "$(jq -c .filesystem "$REC")"; fi

# ---------------------------------------------- R3: the §9a venue (final filesystem, or Ops' own loop image)
fresh own --venue-9a own-image
gen own
OWNREC="$TMP/own/out/production-2000-01-01/record.json"
if [ "$STATUS" = 0 ] && jq -e '.qualification.platform | test("§9a sweep on Ops. own loop image 00000000-0000-4000-8000-00000009a4e0 on / under dm-delay")' "$OWNREC" >/dev/null \
    && grep -q "venue 9a \`loop-image-on-root + dm-delay(slow-W)\`" "$TMP/own/out/production-2000-01-01/PROVENANCE.md" \
    && [ "$(jq -c .filesystem "$OWNREC")" = "$(jq -c .filesystem "$REC")" ]; then
    ok "a §9a venue on Ops' own labelled loop image (other uuid, same identity) builds and says so; the filesystem elements stay the final one"
else bad "own-image §9a venue (exit $STATUS)" "$OUT"; fi
fresh nolabel --venue-9a own-image; edit nolabel qualification-9a/venue.json 'del(d["9a"])'
refused "a §9a venue on another filesystem without the 9a label → refuse" nolabel "is not the final filesystem .*its 9a label is None"
fresh otherlabel --venue-9a own-image; edit otherlabel qualification-9a/venue.json 'd["9a"] = "tmpfs + cgroup io.max"'
refused "a §9a venue labelled anything else → refuse" otherlabel "its 9a label is 'tmpfs \\+ cgroup io.max'"
fresh feat --venue-9a own-image
sed -i.bak 's/ metadata_csum$/ metadata_csum orphan_file/' "$TMP/feat/bundle/qualification-9a/venue-dumpe2fs-h.txt"; rm "$TMP/feat/bundle/qualification-9a/venue-dumpe2fs-h.txt.bak"; resum "$TMP/feat/bundle"
refused "an own-image venue whose filesystem features differ → refuse" feat "venue-dumpe2fs-h.txt: Filesystem features is .*R3: only the uuid and the geometry may differ"
fresh vuuid --venue-9a own-image; edit vuuid qualification-9a/venue.json 'd["filesystemUuid"] = "00000000-0000-4000-8000-0000000000aa"'
refused "an own-image venue whose dumpe2fs is of another filesystem → refuse" vuuid "venue-dumpe2fs-h.txt: Filesystem UUID is '00000000-0000-4000-8000-00000009a4e0'"
fresh nodump --venue-9a own-image; rm "$TMP/nodump/bundle/qualification-9a/venue-dumpe2fs-h.txt"; resum "$TMP/nodump/bundle"
refused "an own-image venue without its dumpe2fs -h → refuse" nodump "required file qualification-9a/venue-dumpe2fs-h.txt is missing"
fresh contra; edit contra qualification-9a/venue.json 'd["9a"] = "loop-image-on-root + dm-delay(slow-W)"'
refused "the final filesystem's uuid labelled as Ops' own image → refuse" contra "names the final filesystem .* but labels the venue"
fresh vkern --venue-9a own-image; edit vkern qualification-9a/venue.json 'd["kernelRelease"] = "0.0.1-other"'
refused "an own-image venue on another kernel → refuse" vkern "qualification-9a/venue.json kernelRelease is '0.0.1-other'"

# ------------------------------------------------------------------- E7 carried (W3 A5): both outcomes
fresh carry --e7 carried
gen carry
if [ "$STATUS" = 0 ] && grep -q "CARRIED-AMENDMENT" "$TMP/carry/out/production-2000-01-01/PROVENANCE.md" \
    && [ "$(jq -r .filesystem.metadataInodesBasis "$TMP/carry/out/production-2000-01-01/record.json")" = "E7 carried under Amendment note (2000-01-01, FAKE-E7-CARRY)" ]; then
    ok "E7 carried with the amendment in the ADR text builds, labelled CARRIED-AMENDMENT"
else bad "E7 carried with amendment (exit $STATUS)" "$OUT"; fi
fresh carryno --e7 carried; printf '# FAKE ADR-035 text, no amendment\n' > "$TMP/carryno/repo/docs/adr/0035-physical-storage-bound-at-ingest.md"
refused "E7 carried without the amendment merged → refuse" carryno "its amendment .* is not in docs/adr/0035"

# -------------------------------------------------- the §9a evaluator reproduces the shipped staging record
S="$REPO/docs/adr/0035-benchmark/qualification/staging-2026-10-08"
SREC="$REPO/backend/storage/src/main/resources/adr035-qualification/staging-amd64-vmi2932906-2026-10-08.json"
E="$(python3 "$GEN" --evaluate-9a "$S")"
if [ "$(jq -c '[.verdict, .trials, .lateCommits, .slowMaxCommitLagAfterWitnessS, .scenarios, .runtimeConfigHashes, .slowSweepStart]' <<<"$E")" = \
     "$(jq -c '["PASS", .qualification.trials, .qualification.lateCommits, .qualification.slowWMaxCommitLagAfterCompletedWitnessS, .qualification.scenarios, [.minio.runtimeConfig.hash], .qualifiedAt]' "$SREC")" ]; then
    ok "the §9a evaluator on the staging corpus reproduces the shipped staging record (trials, late commits, lag, scenarios, config hash, qualifiedAt)"
else bad "§9a evaluator vs staging record" "$E"; fi

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
