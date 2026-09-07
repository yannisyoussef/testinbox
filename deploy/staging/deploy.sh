#!/usr/bin/env bash
# Deploys a specific set of image digests to a staging host (ADR-028/029).
#
# The ordering is the point, and it is not what `docker compose up` does:
#
#   validate digests → pull → run ONE migration job to completion
#     → gate on its exit code → start/update services → wait for READINESS
#
# `up -d` alone would start every service concurrently with the migration and
# report success as soon as the containers existed. A staging deployment is not
# successful because containers are running (§19); this script fails, loudly and
# non-zero, at every step that does not complete.
#
# Rollback is this same script with a previous digest set — see docs/dev/rollback.md.
#
# Required environment (see .env.example): the four *_IMAGE digest references
# plus the environment's own configuration. Nothing here reads a secret value
# into a log.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

: "${TESTINBOX_API_IMAGE:?TESTINBOX_API_IMAGE is required}"
: "${TESTINBOX_INGESTION_IMAGE:?TESTINBOX_INGESTION_IMAGE is required}"
: "${TESTINBOX_WEB_IMAGE:?TESTINBOX_WEB_IMAGE is required}"
: "${TESTINBOX_MIGRATOR_IMAGE:?TESTINBOX_MIGRATOR_IMAGE is required}"

# Self-hosted data services unless the environment supplies managed ones.
COMPOSE_FILES=(-f "$HERE/compose.yaml")
if [[ "${TESTINBOX_BUNDLED_DATA:-true}" == "true" ]]; then
  COMPOSE_FILES+=(-f "$HERE/compose.data.yaml")
fi
compose() { docker compose "${COMPOSE_FILES[@]}" "$@"; }

# `timeout(1)` is GNU coreutils and is absent on a stock macOS. Bounding the
# migration matters (a stuck Flyway advisory lock would otherwise hang the
# deployment indefinitely), but a missing coreutils must not itself abort a
# perfectly good deployment — so it degrades to unbounded, loudly.
run_bounded() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$seconds" "$@"
  else
    echo "warning: no timeout(1) on PATH; running the migration job unbounded" >&2
    "$@"
  fi
}

step() { printf '\n=== %s ===\n' "$1"; }

step "1/5 validate image references"
# Ownership and digest-pinning, before anything is pulled (§29).
"$REPO_ROOT/scripts/validate-image-digest.sh" \
  "$TESTINBOX_API_IMAGE" \
  "$TESTINBOX_INGESTION_IMAGE" \
  "$TESTINBOX_WEB_IMAGE" \
  "$TESTINBOX_MIGRATOR_IMAGE"

step "2/5 pull artifacts"
compose pull --quiet

step "3/5 run the migration job to completion"
# --rm: the job is one-shot. A non-zero exit propagates through `set -e` and
# stops the deployment here, before any service is started or updated.
# Bounded, because Flyway takes a Postgres advisory lock: a stuck prior
# executor would otherwise block this step, and with it the entire deployment
# concurrency group, indefinitely.
if ! run_bounded "${TESTINBOX_MIGRATION_TIMEOUT:-600}" docker compose "${COMPOSE_FILES[@]}" run --rm -T migrator; then
  echo "DEPLOYMENT ABORTED: schema migration failed; services were not touched" >&2
  exit 1
fi

step "4/5 start/update services"
# --wait blocks on the healthchecks, which are readiness probes — so this
# returns only when every service can actually serve TestInbox traffic. That
# holds only because every service has a healthcheck: `--wait` treats one
# without as ready the moment it is running.
if ! compose up -d --wait --wait-timeout "${TESTINBOX_READY_TIMEOUT:-300}" api ingestion web; then
  echo "DEPLOYMENT FAILED: services did not become ready within the timeout" >&2
  compose ps
  exit 1
fi

# The edge is recreated explicitly. Compose considers it up to date on a
# redeploy (same image, same environment) and would leave its workers holding
# the previous configuration and upstream connections — the class of fault that
# only ever shows up on the *second* deployment, which a greenfield rehearsal
# never reaches.
if ! compose up -d --wait --force-recreate --wait-timeout "${TESTINBOX_READY_TIMEOUT:-300}" edge; then
  echo "DEPLOYMENT FAILED: the edge did not become ready within the timeout" >&2
  compose ps
  exit 1
fi

step "5/5 record what is running"
# Deployment evidence (§16/§31). Safe metadata only: commit, version, digest.
for service in api ingestion; do
  port=$([[ "$service" == api ]] && echo 9090 || echo 9091)
  echo "--- $service ---"
  compose exec -T "$service" wget -q -O - "http://127.0.0.1:$port/actuator/info" || echo "(info unavailable)"
  echo
  compose exec -T "$service" wget -q -O - "http://127.0.0.1:$port/actuator/health/readiness" || echo "(readiness unavailable)"
  echo
done

echo
echo "deployment complete"
echo "  api:       $TESTINBOX_API_IMAGE"
echo "  ingestion: $TESTINBOX_INGESTION_IMAGE"
echo "  web:       $TESTINBOX_WEB_IMAGE"
echo "  migrator:  $TESTINBOX_MIGRATOR_IMAGE"
