#!/usr/bin/env bash
# Ops Console 契约检查（CI 零服务依赖，ADR-0009 / 外审④修正版）：
# 面板是纯静态 HTML 消费 /admin/state 与 /admin/audit/recent——后端改名会让面板静默失明。
# 本脚本用字面量比对钉死三层契约，改名即 CI 红；demo.sh 第 7 检（live 键集合）是第二道闸。
# 用法: bash scripts/check_panel_contract.sh
set -euo pipefail
cd "$(dirname "$0")/.."
HTML=src/main/resources/static/index.html
CTRL=src/main/java/com/opspilot/gateway/AdminController.java
MET=src/main/java/com/opspilot/metrics/OpsMetrics.java
fail=0

# 层 1：HTML fetch 的 admin 路径必须存在于 Controller 映射（前缀 /api/v1/admin + @*Mapping 字面量）
mappings=$(grep -oE '@(Get|Post)Mapping\("[^"]+"\)' "$CTRL" | sed -E 's/.*\("([^"]+)"\)/\/api\/v1\/admin\1/' | sort -u)
html_paths=$(grep -oE "/api/v1/admin/[a-z/]+" "$HTML" | sort -u)
for p in $html_paths; do
  echo "$mappings" | grep -qx "$p" || { echo "FAIL: 面板 fetch 的路径无后端映射: $p"; fail=1; }
done

# 层 2：OpsMetrics 的累计计数器键必须全部被面板渲染（tiles 声明含键名字符串）
metric_keys=$(grep -oE 'put\("[a-z_]+"' "$MET" | sed -E 's/put\("([a-z_]+)"/\1/' | sort -u)
for k in $metric_keys; do
  grep -qF "'$k'" "$HTML" || { echo "FAIL: 指标键未进面板 tiles: $k"; fail=1; }
done

# 层 3：面板 tiles 引用的键必须真实存在（OpsMetrics ∪ metricsView 追加键）——
# Java 侧改名（如 total_requests）而 HTML 未同步时，本层点名致红（W11 演练即打这）
tile_keys=$(sed -n '/const keys = \[/,/];/p' "$HTML" | grep -oE "'[a-z_]+'" | tr -d "'" | grep -vE '^(请求|LLM)$' | sort -u)
valid=$( { echo "$metric_keys"; grep -oE 'm\.put\("[a-z_]+"' "$CTRL" | sed -E 's/m\.put\("([a-z_]+)"/\1/'; } | sort -u)
for k in $tile_keys; do
  echo "$valid" | grep -qx "$k" || { echo "FAIL: 面板引用了后端不存在的键: $k（改名漂移？）"; fail=1; }
done

# 层 4：/state 与 recent 的顶层契约键（面板 renderState 消费面 ⊆ Java 装配面）
for k in ts build metrics health runtime; do
  grep -qE "out\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state 顶层装配缺失: $k"; fail=1; }
done
for k in version time jvm uptime_s pid; do
  grep -qE "b\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state build 指纹键缺失: $k"; fail=1; }
done
for k in inflight sf_groups sf_keys_top degradation quota reingest; do
  grep -qE "runtime\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state runtime 键缺失: $k"; fail=1; }
done

# 面板侧消费声明（与 Java 对照的反向字面量存在性）
for k in sf_groups sf_keys_top cooldown_s manual failures threshold used limit busy last; do
  grep -qF "$k" "$HTML" || { echo "FAIL: 面板缺少对运行态键的消费: $k"; fail=1; }
done

if [ $fail -eq 0 ]; then echo "OK：面板契约四层一致（路径/指标→面板/面板→指标/state 键集）"; fi
exit $fail
