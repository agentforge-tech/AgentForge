#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

VERSION="${1:-$(env_get IMAGE_TAG 1.0.0)}"
IMAGE_REPO="$(env_get IMAGE_REPO agentforge-service)"

log "building ${IMAGE_REPO}:${VERSION} (${DOCKER_DIR}/Dockerfile)"
docker build \
    -f "$DOCKER_DIR/Dockerfile" \
    -t "${IMAGE_REPO}:${VERSION}" \
    -t "${IMAGE_REPO}:latest" \
    "$ROOT_DIR"

log "image built: ${IMAGE_REPO}:${VERSION}"
