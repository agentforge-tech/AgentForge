#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ensure_env

DB_TYPE="$(env_get AGENTFORGE_DB_TYPE sqlite)"
mkdir -p "$DOCKER_DIR/backups"

if [ "$DB_TYPE" != "sqlite" ] && [ "$DB_TYPE" != "sqllite" ]; then
    log "AGENTFORGE_DB_TYPE=${DB_TYPE}; use your MySQL backup policy (mysqldump)."
    exit 0
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
DB_FILE="$DOCKER_DIR/data/agentforge.db"
TARGET="$DOCKER_DIR/backups/agentforge-${STAMP}.db"

if [ ! -f "$DB_FILE" ]; then
    log "SQLite database does not exist yet: $DB_FILE"
    exit 0
fi

if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB_FILE" ".backup '$TARGET'"
elif compose ps --status running agentforge >/dev/null 2>&1; then
    log "host sqlite3 not found, stopping container to copy a consistent database"
    compose stop agentforge
    cp "$DB_FILE" "$TARGET"
    compose start agentforge
else
    cp "$DB_FILE" "$TARGET"
fi

log "database backup: $TARGET"
