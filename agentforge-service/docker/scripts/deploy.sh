#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

mkdir -p "$DOCKER_DIR/data" "$DOCKER_DIR/logs" "$DOCKER_DIR/upload"

log "building AgentForge image"
compose build
log "executing SQLite schema migration"
"$SCRIPT_DIR/upgrade-sql.sh"
log "starting AgentForge (SQLite by default)"
compose up -d
compose ps

WEB_PORT="$(env_get WEB_PORT 18080)"
log "web: http://127.0.0.1:${WEB_PORT}"
log "data: ${DOCKER_DIR}/data"
