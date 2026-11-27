#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONF_FILE="$SCRIPT_DIR/deploy.conf"

info() { echo "[INFO] $*"; }
ok() { echo "[ OK ] $*"; }
die() { echo "[FAIL] $*" >&2; exit 1; }

load_conf() {
  [ -f "$CONF_FILE" ] || die "缺少 $CONF_FILE，请先复制 deploy.conf.example"
  # shellcheck disable=SC1090
  source "$CONF_FILE"
  : "${SERVER_IP:?SERVER_IP 未配置}"
  : "${SERVER_USER:?SERVER_USER 未配置}"
  : "${SERVER_DEPLOY_DIR:?SERVER_DEPLOY_DIR 未配置}"
  SERVER_PASSWORD="${SERVER_PASSWORD:-}"
  APP_PORT="${APP_PORT:-17863}"
  JAR_NAME="${JAR_NAME:-agentforge-fim-demo.jar}"
  JAVA_BIN="${JAVA_BIN:-java}"
  REMOTE="${SERVER_USER}@${SERVER_IP}"
  SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)
}

EXPECT_SCRIPT=""
make_expect() {
  EXPECT_SCRIPT="$(mktemp /tmp/fim-demo-remote.XXXXXX)"
  cat > "$EXPECT_SCRIPT" <<'EXPECT'
#!/usr/bin/expect -f
set timeout 3600
set mode [lindex $argv 0]
set host [lindex $argv 1]
set user [lindex $argv 2]
set pass [lindex $argv 3]
if {$mode eq "ssh"} {
  spawn ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $user@$host [lindex $argv 4]
} else {
  spawn scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR [lindex $argv 4] $user@$host:[lindex $argv 5]
}
expect {
  -re "(?i)password:" { send "$pass\r"; exp_continue }
  -re "(?i)yes/no" { send "yes\r"; exp_continue }
  timeout { exit 2 }
  eof
}
catch wait result
exit [lindex $result 3]
EXPECT
  chmod +x "$EXPECT_SCRIPT"
}

cleanup() { [ -z "$EXPECT_SCRIPT" ] || rm -f "$EXPECT_SCRIPT"; }
run_remote() {
  if [ -n "$SERVER_PASSWORD" ]; then
    command -v expect >/dev/null 2>&1 || die "密码登录需要 expect"
    [ -n "$EXPECT_SCRIPT" ] || make_expect
    "$EXPECT_SCRIPT" ssh "$SERVER_IP" "$SERVER_USER" "$SERVER_PASSWORD" "$1"
  else
    ssh "${SSH_OPTS[@]}" "$REMOTE" "$1"
  fi
}
copy_remote() {
  if [ -n "$SERVER_PASSWORD" ]; then
    command -v expect >/dev/null 2>&1 || die "密码登录需要 expect"
    [ -n "$EXPECT_SCRIPT" ] || make_expect
    "$EXPECT_SCRIPT" scp "$SERVER_IP" "$SERVER_USER" "$SERVER_PASSWORD" "$1" "$2"
  else
    scp "${SSH_OPTS[@]}" "$1" "$REMOTE:$2"
  fi
}

