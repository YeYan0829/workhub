#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export WORKHUB_ENV_FILE=/dev/null
export OVERVIEW_JDBC_URL="${OVERVIEW_JDBC_URL:-jdbc:mysql://127.0.0.1:28306/workhub_overview_ui_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}"
exec scripts/run.sh -Pcontracts -Dit.test=ProjectOverviewUIIT "$@" verify
