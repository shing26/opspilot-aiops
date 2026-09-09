#!/usr/bin/env bash
# 初始化演示/验收账号（幂等，已存在则跳过）：
#   sre-limited(level1)/sre-full(level3) 属 tenant-internal；sre-acme(level3) 属 tenant-acme（跨租户红队真实账号）。
# 口令只从 DEMO_PASSWORD 环境变量读取，脚本与 git 均不落任何口令字面量。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -n "${DEMO_PASSWORD:-}" ] || { echo "DEMO_PASSWORD 环境变量未设置"; exit 1; }
add() {
  bash scripts/user_admin.sh add --user "$1" --tenant "$2" --level "$3" --role sre \
      --password-env DEMO_PASSWORD 2>/dev/null || echo "skip（已存在）: $1"
}
add sre-limited tenant-internal 1
add sre-full    tenant-internal 3
add sre-acme    tenant-acme     3
bash scripts/user_admin.sh list
