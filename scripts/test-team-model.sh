#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Team tests require dedicated schemas and loopback Redis/HTTP; no real model credentials.
export WORKHUB_ENV_FILE=/dev/null
exec scripts/run.sh -Pcontracts -Dit.test=TeamModelGovernanceIT,TeamModelRuntimeIT,TeamModelMigrationIT,TeamModelMultiJvmIT,TeamEmbeddingIT "$@" verify
