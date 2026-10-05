#!/usr/bin/env bash
# 取 Ops Console 面板要用的 JWT（role=platform 账号），打印到 stdout。
#
# 为什么单独成脚本而不是让人抄 OPS §9 那行一次性命令：这个 token 是面板的
# 唯一入口，而"token 找不到/贴错了"是实测最容易卡住的一步（面板只收
# role=platform，密级高的 sre-acme 会被 403 拒——ADR-0008：密级高≠可信）。
#
# 安全边界：token 只在**本机终端**打印，不写盘、不入库。粘贴进面板后它落在
# 该浏览器的 localStorage（仅存本地，清=面板右上退出）。别把输出转发到聊天或工单。
#
# 用法：bash scripts/console_token.sh          # 打印 token
#      bash scripts/console_token.sh --copy   # 顺带放进 Windows 剪贴板
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "FAIL: 没有 .env（DEMO_PASSWORD/JWT_SECRET 只从 .env 来）。先跑 scripts/quickstart.sh 生成。" >&2
  exit 1
fi
set -a; . ./.env; set +a
export OPSPILOT_BASE="${OPSPILOT_BASE:-http://localhost:${SERVER_PORT:-8081}}"

# venv 的解释器路径两平台不同（Windows=Scripts/、POSIX=bin/），探测一次
PY=""
for c in offline/.venv/Scripts/python.exe offline/.venv/bin/python; do
  [ -x "$c" ] && { PY="$c"; break; }
done
[ -n "$PY" ] || { echo "FAIL: 找不到 offline/.venv 解释器，先建 venv" >&2; exit 1; }

TOKEN="$(PYTHONPATH=offline "$PY" -c "
import localapi
print(localapi.login('sre-full'))
")"

[ -n "$TOKEN" ] || { echo "FAIL: 取不到 token（网关没起？先 scripts/start_gateway.sh）" >&2; exit 1; }

if [ "${1:-}" = "--copy" ] && command -v clip >/dev/null 2>&1; then
  printf '%s' "$TOKEN" | clip
  echo "已复制到剪贴板，直接粘进面板的凭证框（http://${OPSPILOT_BASE#http://}/ 的『Ops Console 凭证』）。"
else
  echo "$TOKEN"
  echo
  echo "↑ 粘进面板凭证框。只在本机终端里看，别转发。"
fi