---
doc_id: pm-005
service: payment-service
env: prod
auth_level: 2
error_codes: [50031_MQ_CONSUME_LAG, 50042_PAY_SIGN_INVALID]
date: 2026-03-05
severity: P2
---

# 支付回调 MQ 积压复盘（50031_MQ_CONSUME_LAG）

## 事故摘要

2026-03-05 21:00 起，支付回调消费延迟从 200ms 升至 14 分钟，`POST /api/v1/payments/notify` 链路返回 `50031_MQ_CONSUME_LAG`，用户支付成功但订单长时间未更新。

## 根因分析

消费线程内同步调用第三方查单接口，第三方限流导致单条消费耗时从 50ms 飙至 8s，线程池 20 个 worker 全部阻塞。期间混入一批验签失败的回调（`50042_PAY_SIGN_INVALID`），重试策略未区分「可重试」与「不可重试」异常，验签失败消息被无限重投，进一步挤占队列。

```
com.ordercenter.payment.PaymentNotifyConsumer.onMessage(PaymentNotifyConsumer.java:83)
WARN  consume lag 842s, error code 50031_MQ_CONSUME_LAG
ERROR sign verify failed, error code 50042_PAY_SIGN_INVALID -> requeue (BUG: 不应重投)
```

## 修复措施

1. 验签失败改为进死信队列 + 人工对账，不再重投。
2. 消费线程内禁止同步外呼，查单改为异步补偿任务。
3. 积压告警阈值：lag > 60s 即触发 `50031_MQ_CONSUME_LAG`。

## 复盘教训

- 重试策略必须按异常语义分级，`50042_PAY_SIGN_INVALID` 这类确定性失败重投只会放大故障。
- 支付域「已扣款未发货」的客诉优先级高于一切技术指标。
