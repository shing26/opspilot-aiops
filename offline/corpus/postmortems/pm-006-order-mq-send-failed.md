---
doc_id: pm-006
service: order-service
env: prod
auth_level: 2
error_codes: [50032_MQ_SEND_FAILED, 40901_ORDER_STATE_CONFLICT]
date: 2026-03-21
severity: P2
---

# 订单事件投递失败导致状态不一致复盘（50032_MQ_SEND_FAILED）

## 事故摘要

2026-03-21 14:30，RocketMQ Broker 主节点磁盘打满，`POST /api/v1/orders` 下单成功但事件投递失败（`50032_MQ_SEND_FAILED`），下游物流/风控未收到订单创建事件，出现「订单已支付但无法发货」，用户重试下单又触发 `40901_ORDER_STATE_CONFLICT`。

## 根因分析

下单事务采用「先写库、后发 MQ」的弱一致模式，发送失败仅记录日志未落补偿表：

```
com.ordercenter.order.OrderCreateService.createOrder(OrderCreateService.java:171)
ERROR MQ send failed after 3 retries, error code 50032_MQ_SEND_FAILED
      topic=order-events broker=rmq-broker-0:9876
```

## 修复措施

1. 改造为本地消息表（Transactional Outbox）：事件与订单同事务落库，定时任务投递。
2. `50032_MQ_SEND_FAILED` 从 WARN 升级为 P2 告警。
3. 对账任务每 5 分钟扫描「已支付未发货」订单并补发事件。

## 复盘教训

- 「下单成功但下游无感知」是典型的分布式事务缺失，`40901_ORDER_STATE_CONFLICT` 只是用户重试的次生现象。
- Broker 磁盘水位必须纳入容量巡检，`rmq-broker-0` 当时使用率 97%。
