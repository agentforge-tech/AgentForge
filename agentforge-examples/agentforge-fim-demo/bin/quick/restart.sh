#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
load_conf
trap cleanup EXIT
ACTION="${1:-restart}"
case "$ACTION" in start|stop|restart|status) ;; *) die "用法: $0 {start|stop|restart|status}" ;; esac
run_remote "set -e; cd '$SERVER_DEPLOY_DIR'; ./start.sh '$ACTION'"
[ "$ACTION" = stop ] || ok "访问地址: http://${SERVER_IP}:${APP_PORT}/"

