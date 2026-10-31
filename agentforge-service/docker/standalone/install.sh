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
    log "检测到已有部署文件，将保留现有配置并继续安装"
fi

log "下载部署文件"
curl -fsSL "${BASE_URL}/docker-compose.yml" -o docker-compose.yml.tmp
curl -fsSL "${BASE_URL}/upgrade.sh" -o upgrade.sh.tmp
mv docker-compose.yml.tmp docker-compose.yml
mv upgrade.sh.tmp upgrade.sh
chmod +x upgrade.sh

if [ ! -f .env ]; then
    curl -fsSL "${BASE_URL}/env.example" -o .env.tmp
    mv .env.tmp .env
else
    log "保留现有 .env"
fi

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
    -c 'test -f /target/application.yml || cp /opt/agentforge/conf/application.yml /target/application.yml; test -f /target/logback.xml || cp /opt/agentforge/conf/logback.xml /target/logback.xml'

DB_TYPE="$(env_get AGENTFORGE_DB_TYPE sqlite)"
case "$DB_TYPE" in
    sqlite|sqllite|sqlite3)
        log "初始化 SQLite 数据库"
        docker compose run --rm -T --no-deps --entrypoint /opt/agentforge/bin/upgrade-sql.sh agentforge-service
        ;;
    *) log "当前使用 ${DB_TYPE}，跳过 SQLite 迁移" ;;
esac

log "启动 AgentForge"
docker compose up -d agentforge-service
docker compose ps
WEB_PORT="$(env_get WEB_PORT 18080)"
log "安装完成"
printf '\n'
log "本机访问：http://127.0.0.1:${WEB_PORT}"
log "服务器访问：http://<服务器IP>:${WEB_PORT}"
log "配置目录：${PWD}/conf"
log "数据目录：${PWD}/data"
log "查看日志：docker logs -f $(env_get APP_NAME agentforge-service)"
log "升级最新版：./upgrade.sh"
log "升级指定版：./upgrade.sh 1.0.1"
