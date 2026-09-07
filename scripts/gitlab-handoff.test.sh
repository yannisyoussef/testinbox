#!/usr/bin/env bash
# Tests for gitlab-handoff.sh.
#
# This script is the only thing that crosses the GitHub → GitLab trust
# boundary, so what it sends and what it refuses to send are both asserted. A
# stubbed `curl` records the request without anything leaving the machine, and
# the recorded curl config is inspected field by field: "it exited 0" would not
# prove the digests were passed correctly, or that a credential was not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDOFF="$SCRIPT_DIR/gitlab-handoff.sh"

pass=0
fail=0
DIGEST="sha256:$(printf 'c%.0s' {1..64})"
API_DIGEST="sha256:$(printf 'a%.0s' {1..64})"
ING_DIGEST="sha256:$(printf 'b%.0s' {1..64})"
WEB_DIGEST="sha256:$(printf 'd%.0s' {1..64})"
COMMIT="0123456789abcdef0123456789abcdef01234567"
TOKEN="glptt-supersecret-trigger-token-value"

STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

# `curl` that copies the config it was handed (so the payload can be inspected)
# and replies with whatever the current case wants.
write_curl_stub() {
  cat >"$STUB_DIR/curl" <<STUB
#!/usr/bin/env bash
echo "invoked" >> "\$CURL_STUB_MARKER"
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    --config) cp "\$2" "\$CURL_STUB_CONFIG"; shift 2 ;;
    *) shift ;;
  esac
done
cat "\$CURL_STUB_RESPONSE"
STUB
  chmod +x "$STUB_DIR/curl"
}
write_curl_stub

run_handoff() {
  rm -f "$STUB_DIR/marker" "$STUB_DIR/config"
  CURL_STUB_MARKER="$STUB_DIR/marker" \
  CURL_STUB_CONFIG="$STUB_DIR/config" \
  CURL_STUB_RESPONSE="$STUB_DIR/response" \
  PATH="$STUB_DIR:$PATH" \
  GITLAB_TRIGGER_TOKEN="$TOKEN" \
  EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
    "$HANDOFF" "$@" >"$STUB_DIR/stdout" 2>"$STUB_DIR/stderr"
  echo $?
}

ok_response() {
  cat >"$STUB_DIR/response" <<'JSON'
{"id":4242,"iid":7,"status":"created","web_url":"https://gitlab.com/infinity/infinity-core/-/pipelines/4242"}
HTTP_STATUS:201
JSON
}
error_response() {
  cat >"$STUB_DIR/response" <<'JSON'
{"message":"404 Not Found"}
HTTP_STATUS:404
JSON
}

valid_args=(
  --commit "$COMMIT"
  --api "ghcr.io/testowner/testinbox-api@$API_DIGEST"
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST"
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST"
  --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"
)

record() {
  local name="$1" condition="$2"
  if [[ "$condition" == "ok" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name"; fail=$((fail + 1))
  fi
}

expect_field() {
  local name="$1" expected="$2"
  if grep -qF "$expected" "$STUB_DIR/config"; then
    record "$name" ok
  else
    record "$name" no
    echo "       missing from payload: $expected"
  fi
}

# --- the happy path, field by field ------------------------------------------
ok_response
status=$(run_handoff "${valid_args[@]}")
record "a valid release candidate is handed off (exit 0)" "$([[ "$status" == 0 ]] && echo ok || echo no)"
expect_field "environment is fixed to staging"      'variables[TESTINBOX_ENVIRONMENT]=staging'
expect_field "commit SHA is passed exactly"         "variables[TESTINBOX_COMMIT_SHA]=$COMMIT"
expect_field "api digest is passed exactly"         "variables[TESTINBOX_API_DIGEST]=ghcr.io/testowner/testinbox-api@$API_DIGEST"
expect_field "ingestion digest is passed exactly"   "variables[TESTINBOX_INGESTION_DIGEST]=ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST"
expect_field "migrator digest is passed exactly"    "variables[TESTINBOX_MIGRATOR_DIGEST]=ghcr.io/testowner/testinbox-migrator@$DIGEST"
expect_field "web digest is passed exactly"         "variables[TESTINBOX_WEB_DIGEST]=ghcr.io/testowner/testinbox-web@$WEB_DIGEST"
expect_field "pipeline ref is develop"              'form-string = "ref=develop"'

# --- what must NEVER be in the payload ---------------------------------------
forbidden=0
for pattern in SSH_ ssh PRIVATE_KEY DB_PASSWORD S3_SECRET SMTP API_KEY BOOTSTRAP; do
  if grep -qi "$pattern" "$STUB_DIR/config"; then
    echo "       payload contains forbidden material matching '$pattern'"
    forbidden=1
  fi
done
record "no host, SSH, database, object-store, SMTP or API credential in the payload" \
  "$([[ "$forbidden" == 0 ]] && echo ok || echo no)"

# The token must reach curl (it is the credential) but must never be printed.
record "the trigger token is sent to curl" \
  "$(grep -qF "token=$TOKEN" "$STUB_DIR/config" && echo ok || echo no)"
record "the trigger token is never printed" \
  "$(! grep -qF "$TOKEN" "$STUB_DIR/stdout" "$STUB_DIR/stderr" && echo ok || echo no)"

# --- the response is reported without being mistaken for a deployment --------
record "the GitLab pipeline id and url are reported" \
  "$(grep -q '4242' "$STUB_DIR/stdout" && grep -q 'pipelines/4242' "$STUB_DIR/stdout" && echo ok || echo no)"
record "the wording says handoff accepted, never deployment succeeded" \
  "$(grep -q 'RELEASE HANDOFF ACCEPTED' "$STUB_DIR/stdout" \
     && ! grep -qi 'deployment succeeded\|deployed successfully' "$STUB_DIR/stdout" "$STUB_DIR/stderr" \
     && echo ok || echo no)"

# --- refusals: nothing may leave the runner ----------------------------------
refuses() {
  local name="$1"; shift
  ok_response
  local status
  status=$(run_handoff "$@")
  local reached=no
  [[ -f "$STUB_DIR/marker" ]] && reached=yes
  if [[ "$status" != "0" && "$reached" == "no" ]]; then
    record "$name" ok
  else
    record "$name" no
    echo "       exit $status, curl reached=$reached"
  fi
}

refuses "a short commit SHA is refused before any request" \
  --commit "0123456" --api "ghcr.io/testowner/testinbox-api@$API_DIGEST" \
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST" \
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST" --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"

refuses "an uppercase commit SHA is refused" \
  --commit "0123456789ABCDEF0123456789abcdef01234567" --api "ghcr.io/testowner/testinbox-api@$API_DIGEST" \
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST" \
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST" --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"

refuses "a missing digest is refused" \
  --commit "$COMMIT" --api "" \
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST" \
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST" --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"

refuses "a mutable tag instead of a digest is refused" \
  --commit "$COMMIT" --api "ghcr.io/testowner/testinbox-api:develop" \
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST" \
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST" --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"

refuses "an image from another owner is refused" \
  --commit "$COMMIT" --api "ghcr.io/attacker/testinbox-api@$API_DIGEST" \
  --ingestion "ghcr.io/testowner/testinbox-ingestion@$ING_DIGEST" \
  --migrator "ghcr.io/testowner/testinbox-migrator@$DIGEST" --web "ghcr.io/testowner/testinbox-web@$WEB_DIGEST"

# Missing token: refused, and the refusal names the variable rather than a value.
rm -f "$STUB_DIR/marker"
CURL_STUB_MARKER="$STUB_DIR/marker" CURL_STUB_CONFIG="$STUB_DIR/config" CURL_STUB_RESPONSE="$STUB_DIR/response" \
PATH="$STUB_DIR:$PATH" EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
  env -u GITLAB_TRIGGER_TOKEN "$HANDOFF" "${valid_args[@]}" >/dev/null 2>&1
token_status=$?
record "a missing trigger token refuses before any request" \
  "$([[ "$token_status" != "0" && ! -f "$STUB_DIR/marker" ]] && echo ok || echo no)"

# --- a rejected trigger must fail the job ------------------------------------
error_response
status=$(run_handoff "${valid_args[@]}")
record "a non-2xx from GitLab fails the handoff" "$([[ "$status" != "0" ]] && echo ok || echo no)"
record "a failed handoff still does not print the token" \
  "$(! grep -qF "$TOKEN" "$STUB_DIR/stdout" "$STUB_DIR/stderr" && echo ok || echo no)"

echo "----"
echo "gitlab-handoff.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
