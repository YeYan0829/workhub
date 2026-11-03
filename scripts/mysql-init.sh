#!/usr/bin/env bash
set -euo pipefail
# Executed only on first initialization of this project's MySQL volume.
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ && "$MYSQL_USER" =~ ^[A-Za-z0-9_]+$ ]]
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}_contract\`;
GRANT ALL ON \`${MYSQL_DATABASE}_contract\`.* TO '$MYSQL_USER'@'%';
SQL
