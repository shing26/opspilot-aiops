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

# 容器形态守卫（事故 51103 的复现路径）：容器网关经 ./data 挂载持有同一个 H2 账号库，
# 而 H2 的锁文件跨 bind mount 不可靠——两个进程同写会损坏库且恢复工具救不回，
# 账号库又是全系统唯一不可再生的数据。OPS §1 已定"容器形态必须串行"，此处把它变成机制。
if command -v docker >/dev/null 2>&1 \
   && [ "$(docker inspect -f '{{.State.Running}}' opspilot-gateway 2>/dev/null || echo false)" = "true" ]; then
  cat >&2 <<'MSG'
拒绝执行：容器 opspilot-gateway 正在运行，它正持有同一个 H2 账号库（./data 挂载）。
容器 bind mount 上的 H2 锁不可靠，两进程同写会损坏账号库（事故 51103：MVStore chunk
损坏，官方恢复工具也救不回）。请选其一：
  1) 串行执行：      docker compose stop gateway  然后重跑本命令（容器形态下这是常态做法）
  2) 走 HTTP 管理面：curl -X POST http://localhost:8081/api/v1/admin/backup -H "Authorization: Bearer <platform JWT>"
  3) 容器内 CLI：   见 scripts/quickstart.sh 的 seed 形态（docker compose exec + PropertiesLauncher）
MSG
  exit 2
fi

export H2_DB_URL="${H2_DB_URL:-jdbc:h2:file:./data/users;AUTO_SERVER=TRUE}"
export H2_DB_USER="${H2_DB_USER:-opspilot}"
JAVA_BIN="${JAVA_HOME:-/e/java/jdk21}/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
exec "$JAVA_BIN" -cp "$JAR" \
  -Dloader.main=com.opspilot.auth.UserAdminCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
