#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f compose.team-model-test.yaml exec -T mysql sh -ec '
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS workhub_team_model_upgrade_test;
CREATE DATABASE IF NOT EXISTS workhub_model_ui_test;
CREATE DATABASE IF NOT EXISTS workhub_overview_ui_test;
CREATE DATABASE IF NOT EXISTS workhub_overview_realtime_test;
CREATE DATABASE IF NOT EXISTS workhub_overview_benchmark_test;
GRANT ALL ON workhub_team_model_upgrade_test.* TO '\''team_model_fixture'\''@'\''%'\'';
GRANT ALL ON workhub_model_ui_test.* TO '\''team_model_fixture'\''@'\''%'\'';
GRANT ALL ON workhub_overview_ui_test.* TO '\''team_model_fixture'\''@'\''%'\'';
GRANT ALL ON workhub_overview_realtime_test.* TO '\''team_model_fixture'\''@'\''%'\'';
GRANT ALL ON workhub_overview_benchmark_test.* TO '\''team_model_fixture'\''@'\''%'\'';
SQL
'
