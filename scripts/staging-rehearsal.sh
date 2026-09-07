#!/usr/bin/env bash
# Stands the entire staging topology up on this machine, deploys to it exactly
# the way a real host would be deployed to, and runs the post-deployment
# synthetic suite against it.
#
# WHY THIS EXISTS: no staging provider has been chosen (ADR-030 is Proposed,
# VISION.md §7). Everything except the last step of the pipeline can still be
# proven, and this proves it — including the parts that only a *deployed*
# topology can exercise:
#
#   * images built from this commit, pushed to a registry, deployed BY DIGEST;
#   * one migration job run to completion and gated on, applications not
#     migrating at startup;
#   * readiness that actually reflects database, schema, object store and the
#     session-scoped LISTEN connection;
#   * real TLS through a real nginx, with the long-poll read timeout that a
#     stock configuration gets wrong;
#   * the public SDK driving a complete inbound workflow end to end.
#
# It is NOT a deployment: nothing here is reachable off this machine, and
# nothing here selects a hosting provider.
#
# Usage: scripts/staging-rehearsal.sh [--keep]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${REHEARSAL_WORK_DIR:-$REPO_ROOT/.rehearsal}"
REGISTRY_HOST="127.0.0.1:${REHEARSAL_REGISTRY_PORT:-5000}"
REGISTRY_NAME="testinbox-rehearsal-registry"
HTTPS_PORT="${REHEARSAL_HTTPS_PORT:-8443}"
# Raised deliberately when tests are added; a silently shrinking gate is the
# failure this number exists to catch.
SYNTHETIC_MINIMUM=19
HTTP_PORT="${REHEARSAL_HTTP_PORT:-8080}"
SMTP_PORT="${REHEARSAL_SMTP_PORT:-2525}"
KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

COMPOSE=(docker compose -f "$REPO_ROOT/deploy/staging/compose.yaml" -f "$REPO_ROOT/deploy/staging/compose.data.yaml")

step()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
cleanup() {
  local status=$?
  if [[ "$KEEP" == true && $status -eq 0 ]]; then
    echo "--keep: leaving the stack up (docker compose -f deploy/staging/compose.yaml -f deploy/staging/compose.data.yaml down -v)"
    return
  fi
  step "teardown"
  if [[ $status -ne 0 ]]; then
    # Evidence first: a failed rehearsal must leave something to debug (§19).
    echo "--- container status ---"; "${COMPOSE[@]}" ps || true
    for service in migrator api ingestion web edge; do
      echo "--- $service logs (tail) ---"
      "${COMPOSE[@]}" logs --tail=80 "$service" 2>/dev/null || true
    done
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit $status
}
trap cleanup EXIT

# The environment file drives compose, deploy.sh and the synthetic suite alike.
mkdir -p "$WORK/tls"
ENV_FILE="$WORK/.env"

step "1/8 local registry (so images are deployed by real digest, as in production)"
docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
docker run -d --name "$REGISTRY_NAME" -p "$REGISTRY_HOST:5000" registry:3 >/dev/null
# Wait for it rather than sleeping blindly.
for _ in $(seq 1 60); do
  curl -fsS "http://$REGISTRY_HOST/v2/" >/dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://$REGISTRY_HOST/v2/" >/dev/null

step "2/8 build and publish the immutable artifacts"
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
# Plain variables rather than an associative array: macOS ships bash 3.2, and
# this script has to run the same way on a developer machine and on a runner.
build_and_push() {
  local name="$1" dockerfile="$2"; shift 2
  local ref="$REGISTRY_HOST/testinbox-$name"
  docker build -f "$REPO_ROOT/$dockerfile" --build-arg "GIT_SHA=$GIT_SHA" "$@" \
    -t "$ref:rehearsal" "$REPO_ROOT" >&2
  docker push "$ref:rehearsal" >&2
  # The digest is the deployment identity (ADR-028) — read it back from the
  # registry rather than trusting the tag we just wrote.
  # Read the digest back from the REGISTRY for this exact tag. `docker inspect`
  # .RepoDigests is a list whose order is not defined, so index 0 can be a
  # digest from an earlier push of the same repository name — which would mean
  # deploying the wrong bytes, not just a flaky run.
  local digest
  digest="$(docker buildx imagetools inspect "$ref:rehearsal" --format '{{.Manifest.Digest}}')"
  case "$digest" in
    sha256:*) ;;
    *) echo "could not resolve a digest for $ref:rehearsal (got '$digest')" >&2; return 1 ;;
  esac
  echo "$ref@$digest"
}
API_IMAGE="$(build_and_push api       deploy/docker/backend.Dockerfile --build-arg MODULE=api)"
INGESTION_IMAGE="$(build_and_push ingestion deploy/docker/backend.Dockerfile --build-arg MODULE=ingestion)"
MIGRATOR_IMAGE="$(build_and_push migrator  deploy/docker/backend.Dockerfile --build-arg MODULE=migrator)"
WEB_IMAGE="$(build_and_push web       deploy/docker/web.Dockerfile)"
for ref in "$API_IMAGE" "$INGESTION_IMAGE" "$MIGRATOR_IMAGE" "$WEB_IMAGE"; do echo "  $ref"; done

step "3/8 TLS material (a private CA, so verification stays ON in the synthetic suite)"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -keyout "$WORK/tls/ca.key" -out "$WORK/tls/ca.pem" \
  -subj "/CN=TestInbox Rehearsal CA" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes \
  -keyout "$WORK/tls/privkey.pem" -out "$WORK/tls/server.csr" \
  -subj "/CN=localhost" >/dev/null 2>&1
openssl x509 -req -in "$WORK/tls/server.csr" -days 2 \
  -CA "$WORK/tls/ca.pem" -CAkey "$WORK/tls/ca.key" -CAcreateserial \
  -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\n') \
  -out "$WORK/tls/leaf.pem" >/dev/null 2>&1
cat "$WORK/tls/leaf.pem" "$WORK/tls/ca.pem" > "$WORK/tls/fullchain.pem"
# Readable by the nginx container's user, but the private material stays 600.
chmod 644 "$WORK/tls/fullchain.pem" "$WORK/tls/ca.pem"
chmod 600 "$WORK/tls/privkey.pem" "$WORK/tls/ca.key"

step "4/8 environment"
# Generated per run: the rehearsal must never rely on a value that could also
# be a real credential, and DeploymentSafety refuses local-development defaults.
random() { LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c "${1:-40}"; }
cat > "$ENV_FILE" <<ENV
TESTINBOX_API_IMAGE=$API_IMAGE
TESTINBOX_INGESTION_IMAGE=$INGESTION_IMAGE
TESTINBOX_WEB_IMAGE=$WEB_IMAGE
TESTINBOX_MIGRATOR_IMAGE=$MIGRATOR_IMAGE
TESTINBOX_GIT_SHA=$GIT_SHA

TESTINBOX_ENVIRONMENT=rehearsal
TESTINBOX_SERVER_NAME=localhost
TESTINBOX_PUBLIC_BASE_URL=https://localhost:$HTTPS_PORT
TESTINBOX_MAIL_DOMAIN=rehearsal.testinbox.email

TESTINBOX_BUNDLED_DATA=true
TESTINBOX_DB_NAME=testinbox
TESTINBOX_DB_URL=jdbc:postgresql://postgres:5432/testinbox
TESTINBOX_DB_USER=testinbox_rehearsal
TESTINBOX_DB_PASSWORD=$(random 40)
TESTINBOX_S3_ENDPOINT=http://minio:9000
TESTINBOX_S3_ACCESS_KEY=$(random 24)
TESTINBOX_S3_SECRET_KEY=$(random 44)
TESTINBOX_S3_BUCKET=testinbox-mime
TESTINBOX_BOOTSTRAP_API_KEY=tk_reh_$(random 40)

TESTINBOX_WAIT_WINDOW_CAP=60s
TESTINBOX_PROXY_READ_TIMEOUT_SECONDS=120

TESTINBOX_EDGE_RATE_PER_SECOND=200
TESTINBOX_EDGE_BURST=1000
TESTINBOX_EDGE_CONN_LIMIT=256

TESTINBOX_TLS_DIR=$WORK/tls
TESTINBOX_HTTP_BIND=127.0.0.1
TESTINBOX_HTTPS_BIND=127.0.0.1
TESTINBOX_HTTP_PUBLISHED_PORT=$HTTP_PORT
TESTINBOX_HTTPS_PUBLISHED_PORT=$HTTPS_PORT
TESTINBOX_SMTP_BIND=127.0.0.1
TESTINBOX_SMTP_PUBLISHED_PORT=$SMTP_PORT

# Chosen, not inherited: two cold JVMs plus bucket creation on a loaded CI
# runner is the tightest timing in the pipeline.
TESTINBOX_READY_TIMEOUT=420
TESTINBOX_MIGRATION_TIMEOUT=300
ENV
set -a; . "$ENV_FILE"; set +a
export COMPOSE_ENV_FILES="$ENV_FILE"

step "5/8 deploy (the same script a real host runs)"
# The rehearsal registry replaces ghcr.io as the ownership root; the digest
# pinning and image-name checks are exercised unchanged.
EXPECTED_IMAGE_REPOSITORY="$REGISTRY_HOST" "$REPO_ROOT/deploy/staging/deploy.sh"

step "6/8 assert the wait notifier is on a real LISTEN connection"
# Direct evidence, not inference. The synthetic suite can only observe wake-up
# *latency* from outside; here the topology is ours, so read the mechanism
# itself. A transaction-mode pooler would leave listening=false while the
# product still worked — slower — which is precisely why it needs asserting.
READINESS=$("${COMPOSE[@]}" exec -T api wget -q -O - http://127.0.0.1:9090/actuator/health/readiness)
echo "$READINESS"
case "$READINESS" in
  *'"listening":true'*) echo "ok — LISTEN connection is live" ;;
  *) echo "the API is not holding a LISTEN connection (ADR-020); wait latency would silently degrade" >&2; exit 1 ;;
esac

# And that Ops can actually see it. A metric that exists in a unit test but is
# not on the scrape endpoint of the running container is not observability.
SCRAPE=$("${COMPOSE[@]}" exec -T api wget -q -O - http://127.0.0.1:9090/actuator/prometheus)
for metric in testinbox_build testinbox_wait_listen_degraded_polling testinbox_wait_requests_active; do
  case "$SCRAPE" in
    *"$metric"*) echo "ok — $metric is on the scrape endpoint" ;;
    *) echo "$metric is missing from /actuator/prometheus" >&2; exit 1 ;;
  esac
done
# The degraded gauge must read healthy on a stack whose LISTEN connection is up
# — a gauge stuck at 1 would alert forever and be muted within a week.
# Matched by regex, not substring: the exposition format renders the value as
# `0`, `0.0` or `0.000000` depending on the registry, and pinning one spelling
# would make this fail for a formatting reason and look like an outage.
DEGRADED_LINE=$(printf '%s\n' "$SCRAPE" | grep -E '^testinbox_wait_listen_degraded_polling(\{[^}]*\})?[[:space:]]' | head -1)
if printf '%s' "$DEGRADED_LINE" | grep -Eq '[[:space:]]0(\.0*)?$'; then
  echo "ok — degraded-polling gauge reads healthy ($DEGRADED_LINE)"
else
  echo "testinbox_wait_listen_degraded_polling is not 0 on a healthy stack: '$DEGRADED_LINE'" >&2
  exit 1
fi

step "7/8 build the public SDK this commit ships, for the synthetic suite"
( cd "$REPO_ROOT/sdk/typescript" && npm ci --silent && npm run build --silent )
# `npm ci` needs the SDK's dist/ to already exist: the synthetic package
# depends on it through a file: reference.
( cd "$REPO_ROOT/deploy/synthetic" && npm ci --silent --no-audit --no-fund )

step "8/8 post-deployment synthetic verification"
# TLS verification stays ON: Node is pointed at the rehearsal CA rather than
# told to ignore certificates.
# `cd` rather than `npm --prefix`: --prefix relocates package.json but not the
# working directory, so the test runner would look for tests/ in the caller's cwd.
cd "$REPO_ROOT/deploy/synthetic"
mkdir -p build/test-results
NODE_EXTRA_CA_CERTS="$WORK/tls/ca.pem" \
TESTINBOX_BASE_URL="https://localhost:$HTTPS_PORT" \
TESTINBOX_HTTP_BASE_URL="http://localhost:$HTTP_PORT" \
TESTINBOX_API_KEY="$TESTINBOX_BOOTSTRAP_API_KEY" \
TESTINBOX_SMTP_HOST=127.0.0.1 \
TESTINBOX_SMTP_PORT="$SMTP_PORT" \
TESTINBOX_WAIT_WINDOW_SECONDS=60 \
  npm run test:junit

# The synthetic suite is a blocking deployment gate, so it gets the same
# treatment as every other suite (docs/quality/strategy.md): a green exit code
# is not evidence that anything ran. A renamed file or a `test.skip` would
# otherwise shrink the gate silently.
REPORT="$REPO_ROOT/deploy/synthetic/build/test-results/synthetic.xml"
test -f "$REPORT" || { echo "synthetic suite produced no report" >&2; exit 1; }
# Node's junit reporter emits a flat <testsuites> of <testcase> elements with
# no summary attributes, so the elements themselves are what gets counted.
RAN=$(grep -c '<testcase' "$REPORT" || true)
FAILED=$(grep -c '<failure' "$REPORT" || true)
SKIPPED=$(grep -c '<skipped' "$REPORT" || true)
echo "synthetic suite: ${RAN:-0} tests, ${FAILED:-0} failed, ${SKIPPED:-0} skipped"
test "${RAN:-0}" -ge "$SYNTHETIC_MINIMUM" || {
  echo "expected at least $SYNTHETIC_MINIMUM synthetic tests, got ${RAN:-0}" >&2; exit 1; }
test "${FAILED:-0}" -eq 0 || { echo "synthetic tests failed" >&2; exit 1; }
test "${SKIPPED:-0}" -eq 0 || { echo "synthetic tests were skipped" >&2; exit 1; }

step "rehearsal passed"
echo "api:       $API_IMAGE"
echo "ingestion: $INGESTION_IMAGE"
echo "web:       $WEB_IMAGE"
echo "migrator:  $MIGRATOR_IMAGE"
