#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

NEW_TAG="${1:-}"
if [ -n "$NEW_TAG" ]; then
    env_set IMAGE_TAG "$NEW_TAG"
    log "image tag switched to ${NEW_TAG}"
fi

IMAGE_TAG="$(env_get IMAGE_TAG 1.0.0)"
IMAGE_REPO="$(env_get IMAGE_REPO agentforge-service)"
log "upgrading ${IMAGE_REPO}:${IMAGE_TAG}"

# Back up SQLite before replacing the container.
"$SCRIPT_DIR/backup.sh"

if [ "${SKIP_PULL:-0}" = "1" ]; then
    log "SKIP_PULL=1, using the local image"
elif compose pull agentforge >/dev/null 2>&1; then
    log "pulled ${IMAGE_REPO}:${IMAGE_TAG}"
else
    log "pull skipped or image is local; recreate with the current image"
fi

"$SCRIPT_DIR/upgrade-sql.sh"
compose up -d --force-recreate agentforge
compose ps
log "upgrade finished; check logs with: docker logs -f $(env_get APP_NAME agentforge)"
