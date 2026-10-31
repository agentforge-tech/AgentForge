#!/usr/bin/env bash
#
# 执行 SQLite 增量 SQL，并记录到 agentforge_schema_history。
# 该脚本同时用于：
#   1) 本机：agentforge-service/bin/local/upgrate-sqlite.sh
#   2) 容器：/opt/agentforge/bin/upgrade-sql.sh（由 Dockerfile 复制）
#
# 环境变量：
#   AGENTFORGE_DB_TYPE                sqlite（默认）/ mysql；非 sqlite 时直接跳过
#   AGENTFORGE_SQLITE_PATH            SQLite 文件路径
#   AGENTFORGE_SQLITE_INCREMENT_DIR   增量 SQL 目录
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
HISTORY_TABLE="agentforge_schema_history"
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

checksum() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "需要 sha256sum 或 shasum 计算脚本校验和"
    fi
}

if [ "$DRY_RUN" = false ]; then
    mkdir -p "$(dirname "$SQLITE_DB")"
    info "SQLite 数据库：$SQLITE_DB"
    sqlite3 -cmd ".timeout 5000" "$SQLITE_DB" <<SQL || fail "初始化历史表失败"
CREATE TABLE IF NOT EXISTS ${HISTORY_TABLE} (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    script_name TEXT    NOT NULL UNIQUE,
    checksum    TEXT,
    applied_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SQL
else
    warn "dry-run 模式：只列出待执行 SQL，不修改数据库"
fi

PENDING=0
APPLIED=0
while IFS= read -r sql_file; do
    [ -n "$sql_file" ] || continue
    relative="${sql_file#"$SQL_DIR"/}"
    sum="$(checksum "$sql_file")"
    relative_sql="$(printf '%s' "$relative" | sed "s/'/''/g")"

    existing=""
    if [ -f "$SQLITE_DB" ]; then
        existing="$(sqlite3 -noheader -list -cmd ".timeout 5000" "$SQLITE_DB" \
            "SELECT checksum FROM ${HISTORY_TABLE} WHERE script_name = '${relative_sql}'" 2>/dev/null || true)"
    fi

    if [ -n "$existing" ]; then
        if [ "$existing" != "$sum" ]; then
            warn "脚本已执行但内容已变化：$relative（保留现有表结构）"
        fi
        continue
    fi

    PENDING=$((PENDING + 1))
    if [ "$DRY_RUN" = true ]; then
        printf "  - %s\n" "$relative"
        continue
    fi

    info "执行：$relative"
    if ! {
        printf 'BEGIN;\n'
        cat "$sql_file"
        printf '\n'
        printf "INSERT INTO %s (script_name, checksum) VALUES ('%s', '%s');\n" \
            "$HISTORY_TABLE" "$relative_sql" "$sum"
        printf 'COMMIT;\n'
    } | sqlite3 -bail -cmd ".timeout 5000" "$SQLITE_DB"; then
        fail "SQL 执行失败并已回滚：$relative"
    fi
    APPLIED=$((APPLIED + 1))
done < <(find "$SQL_DIR" -type f -name '*.sql' | sort)

if [ "$DRY_RUN" = true ]; then
    if [ "$PENDING" -eq 0 ]; then
        success "没有需要执行的 SQLite 增量 SQL"
    else
        warn "待执行 SQLite 增量 SQL 共 ${PENDING} 个"
    fi
    exit 0
fi

success "SQLite 升级完成：执行 ${APPLIED} 个"
