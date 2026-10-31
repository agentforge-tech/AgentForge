#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

DB_TYPE="$(env_get AGENTFORGE_DB_TYPE sqlite)"
case "$DB_TYPE" in
    sqlite|sqllite|sqlite3) ;;
    *)
        log "AGENTFORGE_DB_TYPE=${DB_TYPE}; skip SQLite migration"
        exit 0
        ;;
esac

log "running SQLite migration inside a one-off container"
compose stop agentforge >/dev/null 2>&1 || true
compose run --rm -T --no-deps --entrypoint /opt/agentforge/bin/upgrade-sql.sh agentforge
log "SQLite migration finished"
