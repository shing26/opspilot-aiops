#!/usr/bin/env bash
# 备份 SOP（OPS.md §8）：只备份**不可再生**的数据。
#   users zip     —— H2 账号库（口令散列 + token_ver 吊销状态）
#   audit tar.gz  —— 合规留痕（logs/，logback 14 天滚动会回收）
# 刻意不备份 ES/Qdrant：派生索引，chunks.jsonl 在 git（ADR-0001），恢复=reingest 36s。
# 路径 A（网关在跑，常态）：HTTP /admin/backup 由 DB 所有者执行，零 TCP 依赖；
# 路径 B（网关已停）：CLI 嵌入式直连。
set -euo pipefail
cd "$(dirname "$0")/.."
DAY=$(date +%F)
mkdir -p backup
if curl -s -o /dev/null --max-time 3 http://localhost:8081/actuator/health; then
  set -a; [ -f .env ] && . ./.env; set +a
  L3=$(cd offline && .venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))")
  curl -sf -X POST http://localhost:8081/api/v1/admin/backup -H "Authorization: Bearer $L3" \
    -H "Content-Type: application/json" \
    -d "{\"to\":\"backup/users-${DAY}.zip\"}" | grep -o '"backup":"[^"]*"' || { echo "HTTP 备份失败"; exit 1; }
  # 产物存在性校验（QA P1-3）：响应说成功≠文件真的在——必须核宿主文件，
  # 防"容器模式落未映射层"造成的每日假绿
  test -s "backup/users-${DAY}.zip" || { echo "响应正常但宿主 backup/users-${DAY}.zip 缺失（容器未挂载 ./backup 卷？）"; exit 1; }
else
  bash scripts/user_admin.sh backup --to "backup/users-${DAY}.zip"
fi
tar czf "backup/audit-${DAY}.tar.gz" logs/ 2>/dev/null || echo "warn: logs/ 为空，跳过审计打包"
find backup -name 'users-*.zip' -mtime +7 -delete
find backup -name 'audit-*.tar.gz' -mtime +14 -delete
ls -la backup/ | tail -3
echo "OK ${DAY} 备份完成（users 保 7 天 / audit 14 天）"
