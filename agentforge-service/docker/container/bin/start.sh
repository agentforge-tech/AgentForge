#!/usr/bin/env bash
set -euo pipefail

APP_HOME="${APP_HOME:-/opt/agentforge}"
cd "$APP_HOME"

mkdir -p data logs upload sql/sqllite

DB_TYPE="${AGENTFORGE_DB_TYPE:-sqlite}"
echo "[agentforge] starting with database type: ${DB_TYPE}"
if [ "$DB_TYPE" = "sqlite" ] || [ "$DB_TYPE" = "sqllite" ]; then
    SQLITE_DB="${AGENTFORGE_SQLITE_PATH:-${APP_HOME}/data/agentforge.db}"
    echo "[agentforge] SQLite database: ${SQLITE_DB}"
    echo "[agentforge] schema migrations are executed by deploy.sh / upgrade.sh (upgrade-sql.sh)"
    if [ ! -f "$SQLITE_DB" ]; then
        echo "[agentforge][WARN] SQLite database not found; run /opt/agentforge/bin/upgrade-sql.sh first" >&2
    fi
else
    echo "[agentforge] external database: ${AGENTFORGE_DB_URL:-}"
fi

exec java ${JAVA_OPTS:-} -Dfile.encoding=UTF-8 -jar "$APP_HOME/lib/app.jar"
