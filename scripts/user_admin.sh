#!/usr/bin/env bash
# 用户管理入口（P2）：包装 UserAdminCli，经 PropertiesLauncher 复用 fat jar 类路径（H2/bcrypt 内置）。
# 口令只从 --password-env 指定的环境变量或交互 stdin 传入，绝不进 argv（避免 ps 泄露）。
# 用法示例:
#   DEMO_PASSWORD=... bash scripts/user_admin.sh add --user alice --tenant tenant-internal --level 1 --role sre --password-env DEMO_PASSWORD
#   bash scripts/user_admin.sh disable --user alice | rotate --user alice | list
set -euo pipefail
cd "$(dirname "$0")/.."
# .env 自动装载（H2_DB_PASSWORD 等），与 run.sh 同构；显式传入的环境变量优先
if [ -f .env ]; then set -a; . ./.env; set +a; fi
JAR=target/opspilot-gateway-1.0.0.jar
[ -f "$JAR" ] || { echo "缺 $JAR（先 mvn package -DskipTests）"; exit 1; }
export H2_DB_URL="${H2_DB_URL:-jdbc:h2:file:./data/users;AUTO_SERVER=TRUE}"
export H2_DB_USER="${H2_DB_USER:-opspilot}"
JAVA_BIN="${JAVA_HOME:-/e/java/jdk21}/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
exec "$JAVA_BIN" -cp "$JAR" \
  -Dloader.main=com.opspilot.auth.UserAdminCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
