---
doc_id: rb-201
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51201_CIRCUIT_BREAKER_NO_SELFHEAL]
date: 2026-09-13
---

# 降级档位不回落排查手册（51201_CIRCUIT_BREAKER_NO_SELFHEAL）

## 适用症状

- `/api/v1/admin/state` 的 `runtime.degradation.level` 长期停在 L2，上游早已恢复
- `metrics.llm_calls` 长时间不增长，`metrics.sop_fallbacks` 持续增长
- 答案内容变成静态 SOP 清单（`mode=sop_fallback`），溯源引用为空
- 重启网关后立刻恢复正常——这是最强的判别信号：**状态在内存里锁住了，不是外部依赖还坏着**

## 排查步骤

### 第一步：确认是不是"外部真的还坏着"

1. 直连上游探针（embedding / rerank / llm 各发一发），**不要用业务报错猜**——降级设计会把外部故障伪装成"检索变差"
2. 看 `metrics.llm_rate_limited` / `llm_network_errors` 是否仍在增长：仍在涨说明上游确实没恢复，不是自锁
3. 看 `runtime.degradation.cooldown_s`：仍在倒计时说明还在冷却窗口内，属正常保护

### 第二步：判定自锁

4. 冷却 `cooldown_s` 已归零、上游探针全绿，但 `level` 仍为 L2 且 `failures` 数值冻结不动 → 判自锁环
5. 对照 `DegradationStateMachine.current()` 的判定顺序：进入 L2 的分支是否绕开了唯一的计数归零路径

### 第三步：恢复正常档位

6. 首选**重启网关**（Level 全在内存，重启即回到 L0，代价是计数器清零）
7. 若不能重启，用管理面手动置档：`POST /api/v1/admin/degrade`（显式档位可覆盖自动判定），恢复后再放开

## 止损操作

1. 确认上游健康后重启网关：`docker compose restart gateway`（宿主裸进程则重起 jar），恢复 `level=L0`
2. 恢复后立刻验证一次真实链路：`/chat/stream` 的 `meta.degradation_level` 应为 L0 且 `metrics.llm_calls` 增长
3. 降级期答案**不写缓存**（设计如此），因此无需清缓存；若手工置过档，记得清 `manual` 标记
4. 核对这一轮的对外承诺：`sop_fallbacks` 期间的请求全部是兜底话术，如有对外 SLA 需要说明

## 升级路径

- 自锁复发（重启后再次出现）→ 带上 `runtime.degradation` 快照与上游探针结果升级到网关维护方
- 需要"不重启就自愈"→ 属状态机设计变更，须先写 ADR（进入/退出路径必须成对声明），再改代码
