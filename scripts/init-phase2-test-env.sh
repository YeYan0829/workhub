#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Dedicated schema/vhost: Phase 2 tests deliberately clear fixtures and remove their own queues.
docker compose exec -T mysql bash -euc '
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_phase2_test;
CREATE DATABASE IF NOT EXISTS workhub_phase2_migration_test;
GRANT ALL ON workhub_phase2_migration_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
GRANT ALL ON workhub_phase2_test.* TO '\''$MYSQL_USER'\''@'\''%'\'';
SQL
'
docker compose exec -T rabbitmq sh -ec '
rabbitmqctl list_vhosts -q name | grep -qx phase2_test || rabbitmqctl add_vhost phase2_test
rabbitmqctl set_permissions -p phase2_test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"
'
