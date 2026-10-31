#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env

VERSION="${1:-$(env_get IMAGE_TAG 1.0.0)}"
IMAGE_REPO="$(env_get IMAGE_REPO agentforge-service)"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER="${BUILDX_BUILDER:-agentforge-builder}"

if [ "$IMAGE_REPO" = "agentforge-service" ]; then
    die "set IMAGE_REPO to a registry namespace first, for example IMAGE_REPO=dockerhub-user/agentforge-service"
fi

docker buildx inspect "$BUILDER" >/dev/null 2>&1 \
    || docker buildx create --name "$BUILDER" --use >/dev/null
docker buildx inspect --bootstrap "$BUILDER" >/dev/null 2>&1 || true

log "pushing ${IMAGE_REPO}:${VERSION} for ${PLATFORMS}"
docker buildx build \
    --builder "$BUILDER" \
    --platform "$PLATFORMS" \
    --provenance=false \
    -f "$DOCKER_DIR/Dockerfile" \
    -t "${IMAGE_REPO}:${VERSION}" \
    -t "${IMAGE_REPO}:latest" \
    --push \
    "$ROOT_DIR"

log "pushed ${IMAGE_REPO}:${VERSION} and :latest"
