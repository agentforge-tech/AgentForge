#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$DOCKER_DIR/../.." && pwd)"
ENV_FILE="$DOCKER_DIR/.env"

log() {
    printf '[agentforge] %s\n' "$*"
}

die() {
    printf '[agentforge][ERROR] %s\n' "$*" >&2
    exit 1
}

ensure_conf() {
    mkdir -p "$DOCKER_DIR/conf"
    if [ ! -f "$DOCKER_DIR/conf/application.yml" ]; then
        cp "$ROOT_DIR/agentforge-service/conf/application.yml.example" \
            "$DOCKER_DIR/conf/application.yml"
        log "created docker/conf/application.yml"
    fi
    if [ ! -f "$DOCKER_DIR/conf/logback.xml" ]; then
        cp "$ROOT_DIR/agentforge-service/conf/logback.xml" "$DOCKER_DIR/conf/logback.xml"
        log "created docker/conf/logback.xml"
    fi
}

ensure_env() {
    if [ ! -f "$ENV_FILE" ]; then
        cp "$DOCKER_DIR/.env.example" "$ENV_FILE"
        log "created $ENV_FILE from .env.example"
    fi
    ensure_conf
}

env_get() {
    local key="$1"
    local fallback="${2:-}"
    local shell_value="${!key:-}"
    local value
    if [ -n "$shell_value" ]; then
        printf '%s' "$shell_value"
        return
    fi
    value="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
    if [ -n "$value" ]; then
        printf '%s' "$value"
    else
        printf '%s' "$fallback"
    fi
}

env_set() {
    local key="$1"
    local value="$2"
    if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
        awk -v k="$key" -v v="$value" '
            BEGIN { FS = OFS = "=" }
            $1 == k { print k "=" v; next }
            { print }
        ' "$ENV_FILE" > "$ENV_FILE.tmp"
        mv "$ENV_FILE.tmp" "$ENV_FILE"
    else
        printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
    fi
}

compose() {
    (cd "$DOCKER_DIR" && docker compose "$@")
}

detect_platform() {
    case "$(uname -m)" in
        x86_64|amd64) echo "linux/amd64" ;;
        aarch64|arm64) echo "linux/arm64" ;;
        armv7l|armhf) echo "linux/arm/v7" ;;
        *) echo "linux/$(uname -m)" ;;
    esac
}

require_docker() {
    command -v docker >/dev/null 2>&1 || die "Docker is required"
    docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required"
}
