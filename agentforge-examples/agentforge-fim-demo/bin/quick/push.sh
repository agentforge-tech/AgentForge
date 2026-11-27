#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
load_conf
trap cleanup EXIT

JAR_PATH="$PROJECT_DIR/target/$JAR_NAME"
[ -f "$JAR_PATH" ] || die "未找到 $JAR_PATH，请先执行 build.sh"
STAGE="$(mktemp -d /tmp/fim-demo-stage.XXXXXX)"
cp "$JAR_PATH" "$STAGE/$JAR_NAME"
cat > "$STAGE/start.sh" <<'REMOTE_START'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ACTION="${1:-restart}"
PID_FILE="app.pid"
stop_service() {
  if [ -f "$PID_FILE" ]; then
    PID="$(cat "$PID_FILE")"
    kill "$PID" >/dev/null 2>&1 || true
    for _ in $(seq 1 15); do kill -0 "$PID" >/dev/null 2>&1 || break; sleep 1; done
    rm -f "$PID_FILE"
  fi
}
case "$ACTION" in
  stop) stop_service; echo STOPPED ;;
  start|restart)
    stop_service
    mkdir -p logs
    nohup __JAVA_BIN__ -jar "__JAR_NAME__" --server.port=__APP_PORT__ > logs/console.log 2>&1 &
    echo $! > "$PID_FILE"
    CODE=""
    for _ in $(seq 1 60); do
      kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1 || { echo "PROCESS_EXITED"; tail -80 logs/console.log; exit 1; }
      CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:__APP_PORT__/api/fim/info || true)"
      [ "$CODE" = "200" ] && break
      sleep 1
    done
    echo "SERVICE_HTTP=$CODE"
    [ "$CODE" = "200" ] || { tail -80 logs/console.log; exit 1; }
    ;;
  status)
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1; then
      echo "RUNNING pid=$(cat "$PID_FILE") SERVICE_HTTP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:__APP_PORT__/api/fim/info || true)"
    else echo NOT_RUNNING; fi
    ;;
  *) echo "用法: $0 {start|stop|restart|status}"; exit 1 ;;
esac
REMOTE_START
sed -i.bak -e "s|__JAVA_BIN__|$JAVA_BIN|g" -e "s|__JAR_NAME__|$JAR_NAME|g" -e "s|__APP_PORT__|$APP_PORT|g" "$STAGE/start.sh"
rm -f "$STAGE/start.sh.bak"
chmod +x "$STAGE/start.sh"
COPYFILE_DISABLE=1 tar -czf "$STAGE/release.tar.gz" -C "$STAGE" "$JAR_NAME" start.sh
info "上传到 $REMOTE:$SERVER_DEPLOY_DIR"
run_remote "mkdir -p '$SERVER_DEPLOY_DIR'"
copy_remote "$STAGE/release.tar.gz" "$SERVER_DEPLOY_DIR/release.tar.gz"
rm -rf "$STAGE"
run_remote "set -e; cd '$SERVER_DEPLOY_DIR'; tar -xzf release.tar.gz; chmod +x start.sh; ./start.sh restart"
ok "部署完成: http://${SERVER_IP}:${APP_PORT}/"

