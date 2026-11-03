#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_phase4b_test;
GRANT ALL ON workhub_phase4b_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
docker compose exec -T rabbitmq sh -ec '
if ! rabbitmqctl list_vhosts -q name | grep -qx phase4b_test; then rabbitmqctl add_vhost phase4b_test; fi
rabbitmqctl set_permissions -p phase4b_test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"
'
