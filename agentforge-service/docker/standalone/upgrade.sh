#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

log() { printf '[agentforge] %s\n' "$*"; }
die() { printf '[agentforge][ERROR] %s\n' "$*" >&2; exit 1; }
compose() { docker compose "$@"; }
env_get() {
    local key="$1" fallback="${2:-}" value
    value="$(grep -E "^${key}=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
    printf '%s' "${value:-$fallback}"
}
env_set() {
    local key="$1" value="$2"
    if grep -qE "^${key}=" .env; then
        awk -v k="$key" -v v="$value" 'BEGIN { FS=OFS="=" } $1==k { print k, v; next } { print }' .env > .env.tmp
        mv .env.tmp .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}

command -v docker >/dev/null 2>&1 || die "Docker 未安装"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 未安装"
[ -f .env ] || die "未找到 .env，请在 AgentForge 部署目录中执行"
[ -f docker-compose.yml ] || die "未找到 docker-compose.yml"

OLD_TAG="$(env_get IMAGE_TAG latest)"
NEW_TAG="${1:-latest}"
APP_NAME="$(env_get APP_NAME agentforge)"
STOPPED=0

rollback_on_error() {
    local exit_code=$?
    if [ "$STOPPED" = "1" ]; then
        env_set IMAGE_TAG "$OLD_TAG"
        docker start "$APP_NAME" >/dev/null 2>&1 || true
        log "升级失败，已尝试恢复旧容器"
    fi
    exit "$exit_code"
}
trap rollback_on_error ERR

env_set IMAGE_TAG "$NEW_TAG"
log "拉取镜像 $(env_get IMAGE_REPO codercl/agentforge-service):${NEW_TAG}"
if ! compose pull agentforge; then
    env_set IMAGE_TAG "$OLD_TAG"
    die "镜像拉取失败，已保留原版本"
fi

compose stop agentforge
STOPPED=1

mkdir -p backups
if [ -f data/agentforge.db ]; then
    BACKUP="backups/agentforge-$(date +%Y%m%d-%H%M%S).db"
    cp data/agentforge.db "$BACKUP"
    log "数据库已备份至 $BACKUP"
fi

DB_TYPE="$(env_get AGENTFORGE_DB_TYPE sqlite)"
case "$DB_TYPE" in
    sqlite|sqllite|sqlite3)
        log "执行 SQLite 增量迁移"
        compose run --rm --no-deps --entrypoint /opt/agentforge/bin/upgrade-sql.sh agentforge
        ;;
    *) log "当前使用 ${DB_TYPE}，跳过 SQLite 迁移" ;;
esac

compose up -d --force-recreate agentforge
STOPPED=0
trap - ERR
compose ps
log "升级完成：${NEW_TAG}"
