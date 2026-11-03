#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# A bounded baseline, without browser binaries, external credentials or fault containers.
exec scripts/run.sh -Pcontracts \
  -Dworkhub.test.crash-repetitions=1 \
  -Dworkhub.test.child-timeout-seconds=180 \
  -Dit.test=SchemaIT,MigrationAndLimitsIT,TaskPermissionsIT,PublisherIT,ExecutionIT,CrashRecoveryIT \
  "$@" verify
