---
doc_id: pm-101
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51005_LEG_TIMEOUT_SILENT_DEGRADE, 51001_MODEL_ARREARAGE]
date: 2026-09-10
severity: P2
---

# 复盘：mock 时代的超时参数在 live 下把混合检索打回单路（51005_LEG_TIMEOUT_SILENT_DEGRADE）

## 事件概述

接入真实 DashScope Key 后冒烟发现：所有 `mode=hybrid` 请求实际返回 `mode: es_only`、`vector_score=0`、rerank 分全 0——双路召回静默退化成单路，而**没有任何一条日志报错**。

## 时间线与根因

- 参数 `leg-timeout-ms=800` 诞生于 mock 期：彼时 embedding 是本地词法计算（<1ms），800ms 富余巨大
- live 实测单条 embedding 2.2-3.7s（含外网 TLS 往返与服务端波动）→ 向量路 100% 超时 → `joinSafe` 返回 null → 降级逻辑判定 es_only
- 降级是**设计特性**（超时隔离防止单路拖死全站），所以没有 error 日志——特性正确、参数过时，故障藏在两者的缝隙里
- 排查中一度怀疑代理干扰，绕开系统代理复测后确认为真实服务端延迟

## 止损与修复

1. 参数化并调大：`leg-timeout-ms: ${LEG_TIMEOUT_MS:4500}`（观测 P99 3.7s + 检索余量）
2. 用延迟实测而非拍脑袋定超时：live 化前先直连探针量化每个外部调用的 p50/p99

## 防复发教训

- **每个"魔数"都隐含环境假设**。后端切换（mock→live、本地→云）时，逐一重审所有时间/大小/阈值参数，而不是只改 key
- 静默降级必须配**可见性兜底**：本项目靠 SearchOutcome 的 `mode/degraded` 字段暴露真实执行路径——降级不可怕，看不见降级才可怕
- 上游账户状态（欠费限流）会把延迟放大 3-10 倍：延迟类告警要区分"自己慢了"还是"对面慢了"，直连探针是最快的分界刀
