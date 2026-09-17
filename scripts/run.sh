#!/usr/bin/env bash
# 开发态起服务：加载 .env 后以 mvn spring-boot:run 启动（改动即生效，不用打包）。
# 用法: scripts/run.sh [--opspilot.ingest=true]
# 生产/演示形态用 jar 或容器（见 DEMO.md §0 / quickstart.sh），不要用本脚本。
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a; . ./.env; set +a
fi
# JAVA_HOME 三档探测（跨平台单点，与 py.sh / user_admin.sh 同款思路）：
# 显式传入 > 本机默认位置 > 让 mvn 用 PATH 上的 java。此前硬编码 E:\java\jdk21，
# 他机/Linux 会把 JAVA_HOME 指到不存在的路径而报错难懂。
if [ -z "${JAVA_HOME:-}" ]; then
  for d in /e/java/jdk21 "$HOME/java/jdk21" /usr/lib/jvm/java-21-openjdk-amd64 /opt/java/openjdk; do
    [ -x "$d/bin/java" ] && { export JAVA_HOME="$d"; break; }
  done
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
  || echo "warn: 未探测到 JDK 21（用 PATH 上的 java：$(command -v java || echo 无)）——需 21+"
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="$*"
