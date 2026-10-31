#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

VERSION="${1:-$(env_get IMAGE_TAG 1.0.0)}"
IMAGE_REPO="$(env_get IMAGE_REPO agentforge-service)"

if [ "${SKIP_BUILD:-0}" = "1" ]; then
    log "SKIP_BUILD=1, packaging the existing ${IMAGE_REPO}:${VERSION}"
else
    "$SCRIPT_DIR/build.sh" "$VERSION"
fi

mkdir -p "$DOCKER_DIR/dist"
OUT="$DOCKER_DIR/dist/$(basename "$IMAGE_REPO")-${VERSION}.tar"
log "saving image to ${OUT}"
docker save "${IMAGE_REPO}:${VERSION}" -o "$OUT"
log "image package ready: ${OUT}"
