#!/usr/bin/env bash
# 初始化演示/验收账号（幂等，已存在则跳过）：
#   sre-limited(level1,sre)/sre-full(level3,platform) 属 tenant-internal；
#   sre-acme(level3,sre) 属 tenant-acme——跨租户矩阵靶：同密级、但**非平台管理员**。
#   口径（QA P0-2/ADR-0008）：customer-admin 不得授 platform；level 是密级不是信任域。
# 口令只从 DEMO_PASSWORD 环境变量读取，脚本与 git 均不落任何口令字面量。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -n "${DEMO_PASSWORD:-}" ] || { echo "DEMO_PASSWORD 环境变量未设置"; exit 1; }
add() {
  bash scripts/user_admin.sh add --user "$1" --tenant "$2" --level "$3" --role "$4" \
      --password-env DEMO_PASSWORD 2>/dev/null || echo "skip（已存在）: $1"
}
add sre-limited tenant-internal 1 sre
add sre-full    tenant-internal 3 platform
add sre-acme    tenant-acme     3 sre
bash scripts/user_admin.sh list
