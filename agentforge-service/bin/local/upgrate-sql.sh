#!/usr/bin/env bash

# 在当前机器直接执行项目增量 SQL，不通过 SSH。
# 待执行文件：sql/increment/**/*.sql
# 执行成功记录：sql/local/<版本目录>/<SQL文件名>

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_FILE="${LOCAL_UPGRADE_CONFIG:-$SCRIPT_DIR/deploy.conf}"
PENDING_SQL_DIR="$PROJECT_ROOT/sql/increment"
EXECUTED_SQL_DIR="$PROJECT_ROOT/sql/local"
WORK_DIR="$EXECUTED_SQL_DIR/.work"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DRY_RUN=false

info() { printf "${BLUE}[INFO]${NC} %s\n" "$1"; }
success() { printf "${GREEN}[SUCCESS]${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}[WARN]${NC} %s\n" "$1"; }
fail() { printf "${RED}[ERROR]${NC} %s\n" "$1" >&2; exit 1; }

usage() {
    cat <<EOF
用法：$0 [--dry-run]

  --dry-run    只列出待执行 SQL，不连接或修改数据库
  -h, --help   显示帮助

默认读取：$CONFIG_FILE
可通过 LOCAL_UPGRADE_CONFIG 指定其他配置文件。
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        -h|--help) usage; exit 0 ;;
        *) fail "未知参数：$1" ;;
    esac
    shift
done

[ -f "$CONFIG_FILE" ] || fail "配置文件不存在：$CONFIG_FILE（请复制 bin/local/deploy.conf.example）"
# shellcheck source=/dev/null
source "$CONFIG_FILE"

: "${DB_HOST:?配置缺少 DB_HOST}"
: "${DB_PORT:?配置缺少 DB_PORT}"
: "${DB_USER:?配置缺少 DB_USER}"
: "${DB_PASSWORD:?配置缺少 DB_PASSWORD}"
: "${DB_NAME:?配置缺少 DB_NAME}"
[[ "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]] || fail "数据库名称仅允许字母、数字和下划线：$DB_NAME"
[ -d "$PENDING_SQL_DIR" ] || fail "待升级 SQL 目录不存在：$PENDING_SQL_DIR"

mkdir -p "$EXECUTED_SQL_DIR" "$WORK_DIR"

PENDING_FILES=()
while IFS= read -r sql_file; do
    [ -n "$sql_file" ] && PENDING_FILES+=("$sql_file")
done < <(find "$PENDING_SQL_DIR" -type f -name '*.sql' | sort)

UPGRADE_FILES=()
UPGRADE_COUNT=0
# 兼容 CentOS 7 的 Bash 4.2：set -u 下不能安全展开空数组。
set +u
for sql_file in "${PENDING_FILES[@]}"; do
    [ -n "$sql_file" ] || continue
    relative_path="${sql_file#$PENDING_SQL_DIR/}"
    if [ -f "$EXECUTED_SQL_DIR/$relative_path" ]; then
        info "跳过已执行 SQL：$relative_path"
    else
        UPGRADE_FILES+=("$sql_file")
        UPGRADE_COUNT=$((UPGRADE_COUNT + 1))
    fi
done
set -u

if [ "$UPGRADE_COUNT" -eq 0 ]; then
    success "没有需要执行的增量 SQL，数据库已是最新版本"
    exit 0
fi

printf "待执行 SQL（共 %s 个）：\n" "$UPGRADE_COUNT"
for sql_file in "${UPGRADE_FILES[@]}"; do
    printf "  - %s\n" "${sql_file#$PENDING_SQL_DIR/}"
done

if [ "$DRY_RUN" = true ]; then
    warn "dry-run 模式：未连接数据库，也未归档 SQL 文件"
    exit 0
fi

command -v mysql >/dev/null 2>&1 || fail "未找到 mysql 客户端"
info "数据库：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
warn "MySQL DDL 可能隐式提交；执行前请确认数据库已有可用备份"

DB_EXISTS=""
if ! DB_EXISTS="$(MYSQL_PWD="$DB_PASSWORD" mysql \
    --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
    --batch --skip-column-names \
    --execute="SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '$DB_NAME'" 2>&1)"; then
    fail "无法连接 MySQL 或检查数据库：$DB_EXISTS"
fi
if [ "$DB_EXISTS" != "$DB_NAME" ]; then
    info "数据库不存在，正在创建：$DB_NAME"
    CREATE_OUTPUT=""
    if ! CREATE_OUTPUT="$(MYSQL_PWD="$DB_PASSWORD" mysql \
        --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
        --default-character-set=utf8mb4 \
        --execute="CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci" 2>&1)"; then
        fail "数据库创建失败：$CREATE_OUTPUT"
    fi
    success "数据库已创建：$DB_NAME"
fi

SUCCESS_COUNT=0
for sql_file in "${UPGRADE_FILES[@]}"; do
    relative_path="${sql_file#$PENDING_SQL_DIR/}"
    filename="$(basename "$relative_path")"
    relative_dir="$(dirname "$relative_path")"
    archive_dir="$EXECUTED_SQL_DIR/$relative_dir"
    work_dir="$WORK_DIR/$relative_dir"
    work_sql="$work_dir/$filename"
    log_file="$work_dir/$filename.log"

    info "执行：$relative_path"
    mkdir -p "$archive_dir" "$work_dir"
    cp -f "$sql_file" "$work_sql"
    if MYSQL_PWD="$DB_PASSWORD" mysql \
        --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
        --database="$DB_NAME" --default-character-set=utf8mb4 --show-warnings \
        < "$work_sql" > "$log_file" 2>&1; then
        cp -f "$sql_file" "$EXECUTED_SQL_DIR/$relative_path"
        rm -f "$work_sql" "$log_file"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        success "执行并记录：$relative_path"
    else
        [ ! -s "$log_file" ] || sed -n '1,200p' "$log_file" >&2
        fail "SQL 执行失败：$relative_path；工作文件和日志保留在 $WORK_DIR"
    fi
done

success "数据库升级完成，成功执行 $SUCCESS_COUNT 个 SQL；记录目录：$EXECUTED_SQL_DIR"
