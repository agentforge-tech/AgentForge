#!/usr/bin/env bash
set -euo pipefail

REPOSITORY="${AGENTFORGE_REPOSITORY:-agentforge-tech/AgentForge}"
REF="${AGENTFORGE_REF:-master}"
BASE_URL="https://raw.githubusercontent.com/${REPOSITORY}/${REF}/agentforge-service/docker/standalone"

log() { printf '[agentforge-installer] %s\n' "$*"; }
die() { printf '[agentforge-installer][ERROR] %s\n' "$*" >&2; exit 1; }
env_get() {
    local key="$1" fallback="${2:-}" value
    value="$(grep -E "^${key}=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
    printf '%s' "${value:-$fallback}"
}

command -v curl >/dev/null 2>&1 || die "curl 未安装"
command -v docker >/dev/null 2>&1 || die "Docker 未安装"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 未安装"

if [ -e docker-compose.yml ] || [ -e upgrade.sh ]; then
    die "当前目录已有 AgentForge 部署文件；升级请执行 ./upgrade.sh [版本]"
fi

log "下载部署文件"
curl -fsSL "${BASE_URL}/docker-compose.yml" -o docker-compose.yml.tmp
curl -fsSL "${BASE_URL}/env.example" -o .env.tmp
curl -fsSL "${BASE_URL}/upgrade.sh" -o upgrade.sh.tmp
mv docker-compose.yml.tmp docker-compose.yml
mv .env.tmp .env
mv upgrade.sh.tmp upgrade.sh
chmod +x upgrade.sh

mkdir -p conf data logs upload backups
IMAGE="$(env_get IMAGE_REPO codercl/agentforge-service):$(env_get IMAGE_TAG latest)"
log "拉取镜像 ${IMAGE}"
docker compose pull agentforge-service

log "导出默认配置"
docker run --rm \
    --entrypoint /bin/bash \
    --user "$(id -u):$(id -g)" \
    -v "$PWD/conf:/target" \
    "$IMAGE" \
    -c 'cp /opt/agentforge/conf/application.yml /target/application.yml && cp /opt/agentforge/conf/logback.xml /target/logback.xml'

DB_TYPE="$(env_get AGENTFORGE_DB_TYPE sqlite)"
case "$DB_TYPE" in
    sqlite|sqllite|sqlite3)
        log "初始化 SQLite 数据库"
        docker compose run --rm --no-deps --entrypoint /opt/agentforge/bin/upgrade-sql.sh agentforge-service
        ;;
    *) log "当前使用 ${DB_TYPE}，跳过 SQLite 迁移" ;;
esac

log "启动 AgentForge"
docker compose up -d agentforge-service
docker compose ps
log "安装完成：http://127.0.0.1:$(env_get WEB_PORT 18080)"
log "后续升级：./upgrade.sh [版本]"
