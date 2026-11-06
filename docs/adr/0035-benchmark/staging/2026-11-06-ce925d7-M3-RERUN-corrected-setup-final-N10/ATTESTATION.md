# M3 rerun — workload attestation (WS-B3)

**During 2026-11-06 23:08:13Z–23:17:36Z no other Ops workload ran on vmi2932906.**

Basis (all files in this directory):
- `logs/baseline-pre.txt` (23:08:07Z) and `logs/baseline-post.txt` (after the run): `uptime`, `ps -eo pid,user,pcpu,etime,args
  --sort=-pcpu | head -60`, `ps -eo pid,ppid,etimes,pcpu,args`, `docker ps`, `systemctl list-timers --all`, and the pgrep for
  `selftest|compose|gradle|GradleWorkerMain|pytest|test_.*\.sh|bats|lab`. The pgrep matches only estate GitLab processes (`gitlab`
  in their paths) and cadvisor; there is no test, build, selftest or compose-render process. `logs/ps-forest-{pre,post}.txt` hold
  the process trees.
- `logs/ps-snapshots.log`: `ps` + `docker ps` every 60 s from 23:02:35Z (pre-window) to the end, 15 snapshots. No gradle, selftest,
  pytest, test_*.sh, bats or compose-render process appears.
- `docker ps` before the start: 32 containers, the same set as the M3 baseline (`m3/logs/precheck-go.txt`); no `ws-*`, lab or test
  containers besides this run's own `ws-b3-pg` / `ws-b3-sle-m3-rerun` during the run.
- Settle check (`logs/settle.log`, `settle.sh`): load1 < 4, apt-daily / apt-daily-upgrade / logrotate / dpkg-db-backup inactive and
  0 test/build processes for 13 consecutive 10 s samples, 23:06Z–23:08Z (the jar rebuild ran 22:59:08–23:02Z, before the
  samplers and the settle window; a first settle loop v1 was discarded because its
  pgrep matched itself). Pre-window mpstat (2 min before the start): mean host busy 1.62 cores (`logs/prewindow-cpu.txt`).
- Timing: started after the 23:00Z timers (logrotate, dpkg-db-backup, aeroclub-salt-rotate inactive at 23:02Z), ended 23:17:36Z,
  before the 00:30Z backup timer.
- The coordinator told all other Ops agents to pause (coordinator message). This attestation is from process evidence only.

**Co-resident estate services seen (not paused, as required; all present in the M3 baseline too):**
- GitLab (`gitlab` container: sidekiq, puma, gitaly, its postgres/redis).
- Monitoring/alerting: prometheus, grafana, loki, promtail, node-exporter, cadvisor, uptime-kuma, mon-socket-proxy.
- Edge: traefik, traefik-socket-proxy. Identity/secrets: authentication-*, keycloak*, vault-vault-1.
- Production-serving: nkama-prod-* (incl. rabbitmq-diagnostics health checks), nkama-wellknown-web-1. Staging: testinbox-*,
  sms-sink-dev-sink-1.
- Periodic estate timers observed in the snapshots: `infinity-vault-health` / `infinity-vault-unseal` (every minute: `docker
  compose … exec vault vault status`), the nkama deploy poller (`docker pull -q registry.yvnn.is/nkama/nkama:{dev,prod}`), the
  infinity-deploy poller (`docker manifest inspect …/testinbox-web:develop`), the edge collector (`ssh … edge-collector`), and
  docker inspect/ps health probes. No deploy took place (no container restart: `docker ps` set and uptimes unchanged).

Host during the run (`evidence/host-contamination.md`): load1 mean 3.5, max 4.2; steal 0 % in all 109 intervals; non-benchmark CPU
median 1.92 cores, p95 2.64 (c10dd3d reference: 1.75 / 3.69); no sample flagged, 0 of 8 scenarios marked.
