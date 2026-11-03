#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_phase3x_test;
CREATE DATABASE IF NOT EXISTS workhub_phase3x_migration_test;
GRANT ALL ON workhub_phase3x_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
GRANT ALL ON workhub_phase3x_migration_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
