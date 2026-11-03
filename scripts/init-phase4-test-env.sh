#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_phase4_test;
GRANT ALL ON workhub_phase4_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
docker compose exec -T rabbitmq sh -ec '
if ! rabbitmqctl list_vhosts -q name | grep -qx phase4_test; then rabbitmqctl add_vhost phase4_test; fi
rabbitmqctl set_permissions -p phase4_test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"
'
