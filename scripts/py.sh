#!/usr/bin/env bash
# Python 解释器三档探测（跨平台单点，demo.sh/backup.sh/quickstart.sh 共用）：
# Windows venv → POSIX venv → 系统 python3/python。找不到则空输出+退出码 1，调用方决定 SKIP 语义。
# 背景：此前各脚本硬编码 offline/.venv/Scripts/python.exe（Windows 专用），
# 干净 Linux 上依赖健康检查会静默 SKIP——2026-09-13 公开前 Linux 客串实测暴露（台账 S2a）。
cd "$(dirname "$0")/.."
for p in offline/.venv/Scripts/python.exe offline/.venv/bin/python python3 python; do
  if command -v "$p" >/dev/null 2>&1 || [ -x "$p" ]; then
    echo "$p"
    exit 0
  fi
done
exit 1
