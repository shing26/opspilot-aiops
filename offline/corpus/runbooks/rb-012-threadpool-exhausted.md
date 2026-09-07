---
doc_id: rb-012
service: order-service
env: prod
auth_level: 1
error_codes: [50092_THREAD_POOL_EXHAUSTED]
---

# 线程池耗尽排查手册（50092_THREAD_POOL_EXHAUSTED）

## 适用症状

- 异步任务大面积排队/拒绝，日志 `task rejected, pool exhausted`
- 错误码 `50092_THREAD_POOL_EXHAUSTED`

## 排查步骤

### 第一步：看线程池水位

```bash
curl -s localhost:8080/actuator/metrics/executor.threads.active | jq
curl -s localhost:8080/actuator/metrics/executor.queue.remaining | jq
```

active=max 且 queue.remaining=0 → 池满。

### 第二步：线程在忙什么

```bash
jstack <pid> | grep -A 15 "pool-N-thread"
```

大量线程 BLOCKED 在同一外部调用 → 下游拖垮（本例常为 `50151_FEIGN_TIMEOUT`，见 pm-013）。

### 第三步：确认任务类型分布

按业务（积分/通知/风控）统计各池拒绝数，判断是否需要拆分。

## 止损操作

1. 熔断拖慢线程的外部调用，失败转补偿表。
2. 临时扩 max 线程数 + 队列（治标）。
3. 根本：按业务隔离线程池，拒绝策略选 CallerRuns 还是 Abort 要显式声明。

## 升级路径

- 核心链路（下单）被异步任务拖垮 → P1。
- 补偿表积压 >10 万 → 升级域 Owner。
