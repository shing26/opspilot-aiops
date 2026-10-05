#!/usr/bin/env bash
# 开发态起服务：加载 .env 后以 mvn spring-boot:run 启动（改动即生效，不用打包）。
# 用法: scripts/run.sh [--opspilot.ingest=true]
# 生产/演示形态用 jar 或容器（见 DEMO.md §0 / quickstart.sh），不要用本脚本。
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a; . ./.env; set +a
fi
# JAVA_HOME 探测见 scripts/java_home.sh（跨平台单点：显式传入 > 本机默认位置 > PATH；
# 此前硬编码 E:\java\jdk21，他机/Linux 会把 JAVA_HOME 指到不存在的路径而报错难懂）
# shellcheck source=scripts/java_home.sh
. "$(dirname "$0")/java_home.sh"
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="$*"
