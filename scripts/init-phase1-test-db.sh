#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Isolated test database only. Never cleans the application or Phase 0 schema.
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ && "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}_phase1_test\`;
GRANT ALL ON \`${MYSQL_DATABASE}_phase1_test\`.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
