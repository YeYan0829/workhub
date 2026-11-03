#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_phase5a_test;
GRANT ALL ON workhub_phase5a_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
CREATE DATABASE IF NOT EXISTS workhub_phase5a_mq_test;
GRANT ALL ON workhub_phase5a_mq_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
docker compose exec -T rabbitmq sh -ec '
if ! rabbitmqctl list_vhosts -q name | grep -x phase5a_test >/dev/null; then rabbitmqctl add_vhost phase5a_test; fi
rabbitmqctl set_permissions -p phase5a_test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"
'
