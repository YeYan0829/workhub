#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f "${WORKHUB_ENV_FILE:-.env}" ]]; then set -a; source "${WORKHUB_ENV_FILE:-.env}"; set +a; fi
source scripts/java-env.sh
mkdir -p .tools
artifact_hash=$(sha256sum target/workhub-0.0.1.jar | cut -d " " -f 1)
runtime_path=".tools/runtime/$artifact_hash/workhub-0.0.1.jar"
mkdir -p "$(dirname "$runtime_path")"
if [[ ! -f "$runtime_path" ]]; then cp target/workhub-0.0.1.jar "$runtime_path"; fi
echo $$ > .tools/app.pid
exec "$WORKHUB_JAVA" \
  -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -jar "$runtime_path" --debug=false
