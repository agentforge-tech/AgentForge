#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
load_conf
command -v mvn >/dev/null 2>&1 || die "未找到 Maven"
info "构建 Spring Boot fat jar ..."
cd "$PROJECT_DIR"
mvn -B -q clean package -DskipTests
SOURCE_JAR="$PROJECT_DIR/target/agentforge-fim-demo-1.0.0.jar"
[ -f "$SOURCE_JAR" ] || die "未生成 $SOURCE_JAR"
cp "$SOURCE_JAR" "$PROJECT_DIR/target/$JAR_NAME"
ok "构建完成: target/$JAR_NAME"

