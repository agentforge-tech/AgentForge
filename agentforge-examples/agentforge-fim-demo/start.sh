#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"

echo "[1/2] Building AgentForge model dependencies from source..."
mvn -f "$REPO_DIR/pom.xml" \
  -pl agentforge-model/agentforge-model-registry \
  -am install \
  -DskipTests \
  -Dspotless.check.skip=true

echo "[2/2] Starting AgentForge FIM demo..."
echo "Open http://localhost:${SERVER_PORT:-8080} after startup."
exec mvn -f "$DEMO_DIR/pom.xml" spring-boot:run
