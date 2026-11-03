#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f "${WORKHUB_ENV_FILE:-.env}" ]]; then set -a; source "${WORKHUB_ENV_FILE:-.env}"; set +a; fi
source scripts/java-env.sh
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/.tools/m2}"
exec ./mvnw "$@"
