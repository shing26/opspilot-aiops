---
doc_id: rb-101
service: dashscope-api
env: local
auth_level: 1
error_codes: [51001_MODEL_ARREARAGE, 51005_LEG_TIMEOUT_SILENT_DEGRADE]
date: 2026-09-10
---

# 模型 API 欠费导致流式链路全灭排查手册（51001_MODEL_ARREARAGE）

## 适用症状

- `/chat/stream` 返回 `error` 事件（code=PIPELINE_ERROR），meta 正常但无 delta
- 重灌（reingest）耗时从 <1min 恶化到 6min+，或 embedding 调用报 400/403
- 表面看像"代码坏了"，实际是云端账户状态问题——最坑的是**部分端点还活着**（search 可用，只是 rerank 静默回退）

## 排查步骤

1. 直连三端点探针分离故障面（embedding / rerank / llm 各一发小请求），**不要用业务报错猜**——网关的降级设计会把云故障伪装成"检索变差"
2. 看 HTTP 码与错误体：`Arrearage` / `Access denied ... overdue-payment` = 账户欠费或免费额度耗尽；429 = 限流（不同处置）
3. `GET /api/v1/admin/metrics` 的 `backend` 字段确认服务自报的真实后端（live/mock），排除"以为 live 其实 mock"的口径混乱
4. 区分免费额度与欠费：百炼计费顺序是各模型免费池先扣、扣完走余额；控制台「费用→免费额度」页逐模型看余量

## 止损操作

1. 小额充值解除欠费（个人项目一次全量重灌+验收实测几毛~几块），或到控制台申请/更换可用 Key
2. 等待充值期间设 `DASHSCOPE_MODE=mock` 重启——机制全可用（双模是内建能力），只是语义指标退化为词法代理，不影响功能演示
3. 恢复后必须重灌一次（限流期的慢响应会污染 leg 超时统计）：`POST /api/v1/admin/reingest`

## 防复发

- 给账户余额设云监控告警（低于 ¥10 短信提醒）；`daily_usage` 的 refuse_rate 突增是欠费/限流的上游信号
