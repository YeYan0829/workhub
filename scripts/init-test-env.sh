#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# These scripts create isolated schemas/vhosts; they do not clear application data.
for setup in init-phase1-test-db.sh init-phase2-test-env.sh init-phase3-test-env.sh \
  init-phase3x-test-env.sh init-phase4-test-env.sh init-phase4b-test-env.sh \
  init-phase5a-test-env.sh; do
  "scripts/$setup"
done
