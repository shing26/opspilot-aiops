---
doc_id: rb-004
service: payment-service
env: prod
auth_level: 1
error_codes: [50031_MQ_CONSUME_LAG, 50032_MQ_SEND_FAILED]
---

# MQ 积压与发送失败排查手册（50031_MQ_CONSUME_LAG / 50032_MQ_SEND_FAILED）

## 适用症状

- 支付回调延迟、订单状态不更新，返回 `50031_MQ_CONSUME_LAG`
- 下单/退款事件投递失败，返回 `50032_MQ_SEND_FAILED`

## 排查步骤

### 消费积压（50031）

```bash
# RocketMQ 控制台或 CLI
sh mqadmin consumerProgress -g payment-notify-consumer -n rmq-namesrv:9876
```

看 `Diff Total`（积压量）与消费 TPS。积压增长但 TPS 正常 → 生产速率暴涨；TPS 掉底 → 消费线程阻塞。

```bash
# 线程阻塞定位
jstack <pid> | grep -A 20 "ConsumeMessageThread"
```

### 发送失败（50032）

```bash
sh mqadmin clusterList -n rmq-namesrv:9876
```

重点看 Broker `BID` 与磁盘水位（`#Disk`）。`50032_MQ_SEND_FAILED` 高频出现时优先怀疑 Broker 磁盘满或主从切换。

## 止损操作

1. 积压：临时扩容消费者实例（注意分区数上限）。
2. 消费线程阻塞在外部调用：熔断外呼，消息转补偿表延后处理。
3. 发送失败：切换备用 Broker 组；本地消息表兜底重投。

## 升级路径

- 积压 >100 万条或延迟 >30 分钟 → 升级中间件组 + 支付域 Owner。
- 涉及资金状态不一致 → 立即启动对账流程。
