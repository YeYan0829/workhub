#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export WORKHUB_ENV_FILE=/dev/null
exec scripts/run.sh -Pcontracts -Dit.test=ProjectOverviewRealtimeIT,ProjectOverviewUIIT "$@" verify
