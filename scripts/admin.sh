#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f "${WORKHUB_ENV_FILE:-.env}" ]]; then set -a; source "${WORKHUB_ENV_FILE:-.env}"; set +a; fi
if [[ "${1:-}" == "--database" ]]; then
 [[ "${2:-}" =~ ^[A-Za-z0-9_]+$ ]] || { echo "Invalid database name" >&2; exit 1; }
 export MYSQL_DATABASE="$2"; shift 2
fi
source scripts/java-env.sh
exec "$WORKHUB_JAVA" -Dloader.main=dev.workhub.admin.DeploymentAdmin -cp target/workhub-0.0.1.jar org.springframework.boot.loader.launch.PropertiesLauncher "$@"
