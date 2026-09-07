#!/usr/bin/env bash
# 加载 .env 并以指定 profile 启动 OpsPilot 网关。
# 用法: scripts/run.sh [--opspilot.ingest=true]
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a; . ./.env; set +a
fi
export JAVA_HOME="E:\\java\\jdk21"
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="$*"
