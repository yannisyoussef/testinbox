#!/usr/bin/env bash
# ADR-035 T1 local sanity benchmark (TI-STORAGE-002 §39): the REAL
# JdbcStorageAdmission + StorageAdmission against PostgreSQL 16 in
# Testcontainers. Not the §11 staging enablement gate; laptop numbers are not
# production evidence. It catches an accidental per-recipient query pattern or
# a collapse under a live reservation backlog.
set -euo pipefail
cd "$(dirname "$0")/../../../../backend"
./gradlew :persistence:test -PstorageBenchmark --tests '*StorageAdmissionBenchmark*' --rerun-tasks -i \
  | sed -n '/^| Scenario/,/^$/p'
