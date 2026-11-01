#!/usr/bin/env bash
#
# 执行 SQLite 增量 SQL。
# 该脚本同时用于：
#   1) 本机：agentforge-service/bin/local/upgrate-sqlite.sh
#   2) 容器：/opt/agentforge/bin/upgrade-sql.sh（由 Dockerfile 复制）
#
# 待执行文件：sql/sqllite/**/*.sql
# 执行成功记录：sql/local/sqlite/<版本目录>/<SQL文件名>
#
# 环境变量：
#   AGENTFORGE_DB_TYPE                sqlite（默认）/ mysql；非 sqlite 时直接跳过
#   AGENTFORGE_SQLITE_PATH            SQLite 文件路径
#   AGENTFORGE_SQLITE_INCREMENT_DIR   增量 SQL 目录
#   AGENTFORGE_SQLITE_APPLIED_DIR     执行归档目录
#
# 用法：
#   ./upgrate-sqlite.sh [--dry-run]

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -n "${APP_HOME:-}" ]; then
    SERVICE_HOME="$APP_HOME"
else
    SERVICE_HOME="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi

DB_TYPE="${AGENTFORGE_DB_TYPE:-sqlite}"
SQLITE_DB="${AGENTFORGE_SQLITE_PATH:-$SERVICE_HOME/data/agentforge.db}"
SQL_DIR="${AGENTFORGE_SQLITE_INCREMENT_DIR:-$SERVICE_HOME/sql/sqllite}"
EXECUTED_SQL_DIR="${AGENTFORGE_SQLITE_APPLIED_DIR:-$SERVICE_HOME/sql/local/sqlite}"
WORK_DIR="$EXECUTED_SQL_DIR/.work"
DRY_RUN=false

BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info() { printf "${BLUE}[sqlite]${NC} %s\n" "$1"; }
success() { printf "${GREEN}[sqlite]${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}[sqlite]${NC} %s\n" "$1"; }
fail() { printf "${RED}[sqlite][ERROR]${NC} %s\n" "$1" >&2; exit 1; }

usage() {
    cat <<EOF
用法：$0 [--dry-run]

  --dry-run    只列出待执行 SQL，不修改数据库
  -h, --help   显示帮助

SQLite 数据库：$SQLITE_DB
增量 SQL 目录：$SQL_DIR
执行归档目录：$EXECUTED_SQL_DIR
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

case "$DB_TYPE" in
    sqlite|sqllite|sqlite3) ;;
    *)
        info "AGENTFORGE_DB_TYPE=${DB_TYPE}，跳过 SQLite 增量 SQL"
        exit 0
        ;;
esac

command -v sqlite3 >/dev/null 2>&1 || fail "未找到 sqlite3 客户端"
[ -d "$SQL_DIR" ] || fail "SQLite 增量 SQL 目录不存在：$SQL_DIR"

mkdir -p "$EXECUTED_SQL_DIR" "$WORK_DIR"

PENDING_FILES=()
while IFS= read -r sql_file; do
    [ -n "$sql_file" ] && PENDING_FILES+=("$sql_file")
done < <(find "$SQL_DIR" -type f -name '*.sql' | sort)

UPGRADE_FILES=()
UPGRADE_COUNT=0
set +u
for sql_file in "${PENDING_FILES[@]}"; do
    [ -n "$sql_file" ] || continue
    relative_path="${sql_file#"$SQL_DIR"/}"
    if [ -f "$EXECUTED_SQL_DIR/$relative_path" ]; then
        info "跳过已执行 SQL：$relative_path"
    else
        UPGRADE_FILES+=("$sql_file")
        UPGRADE_COUNT=$((UPGRADE_COUNT + 1))
    fi
done
set -u

if [ "$UPGRADE_COUNT" -eq 0 ]; then
    success "没有需要执行的 SQLite 增量 SQL，数据库已是最新版本"
    exit 0
fi

printf "待执行 SQL（共 %s 个）：\n" "$UPGRADE_COUNT"
for sql_file in "${UPGRADE_FILES[@]}"; do
    printf "  - %s\n" "${sql_file#"$SQL_DIR"/}"
done

if [ "$DRY_RUN" = true ]; then
    warn "dry-run 模式：未连接数据库，也未归档 SQL 文件"
    exit 0
fi

mkdir -p "$(dirname "$SQLITE_DB")"
info "SQLite 数据库：$SQLITE_DB"

SUCCESS_COUNT=0
for sql_file in "${UPGRADE_FILES[@]}"; do
    relative_path="${sql_file#"$SQL_DIR"/}"
    filename="$(basename "$relative_path")"
    relative_dir="$(dirname "$relative_path")"
    archive_dir="$EXECUTED_SQL_DIR/$relative_dir"
    work_dir="$WORK_DIR/$relative_dir"
    work_sql="$work_dir/$filename"
    log_file="$work_dir/$filename.log"

    info "执行：$relative_path"
    mkdir -p "$archive_dir" "$work_dir"
    cp -f "$sql_file" "$work_sql"
    if {
        printf 'BEGIN;\n'
        cat "$work_sql"
        printf '\n'
        printf 'COMMIT;\n'
    } | sqlite3 -bail -cmd ".timeout 5000" "$SQLITE_DB" > "$log_file" 2>&1; then
        cp -f "$sql_file" "$EXECUTED_SQL_DIR/$relative_path"
        rm -f "$work_sql" "$log_file"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        success "执行并记录：$relative_path"
    else
        [ ! -s "$log_file" ] || sed -n '1,200p' "$log_file" >&2
        fail "SQL 执行失败并已回滚：$relative_path；工作文件和日志保留在 $WORK_DIR"
    fi
done

success "SQLite 升级完成，成功执行 $SUCCESS_COUNT 个 SQL；记录目录：$EXECUTED_SQL_DIR"
