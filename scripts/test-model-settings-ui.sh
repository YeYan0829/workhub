#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export WORKHUB_ENV_FILE=/dev/null
export TEAM_MODEL_JDBC_URL="${TEAM_MODEL_JDBC_URL:-jdbc:mysql://127.0.0.1:28306/workhub_model_ui_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}"
exec scripts/run.sh -Pcontracts -Dit.test=ModelSettingsUIIT "$@" verify
