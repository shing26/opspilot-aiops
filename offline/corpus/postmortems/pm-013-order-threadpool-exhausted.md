---
doc_id: pm-013
service: order-service
env: prod
auth_level: 2
error_codes: [50092_THREAD_POOL_EXHAUSTED, 50151_FEIGN_TIMEOUT]
date: 2026-07-01
severity: P2
---

# 订单异步线程池耗尽复盘（50092_THREAD_POOL_EXHAUSTED）

## 事故摘要

2026-07-01 16:20，订单后置处理（积分、通知、风控上报）异步任务大面积排队，`50092_THREAD_POOL_EXHAUSTED`，下单接口虽成功但积分延迟 8 分钟到账。

## 根因分析

风控上报经 Feign 调用外部服务超时（`50151_FEIGN_TIMEOUT`），每个任务占用线程 10s，线程池 core=20/max=50/queue=1000 迅速打满，积分与通知任务被饿死：

```
com.ordercenter.order.PostOrderExecutor.execute(PostOrderExecutor.java:77)
ERROR task rejected, pool exhausted, error code 50092_THREAD_POOL_EXHAUSTED
      active=50 queue=1000 completed=182943
```

## 修复措施

1. 按业务拆分线程池：积分、通知、风控各自独立，互不饿死。
2. 风控上报超时收紧至 2s + 熔断，失败降级为本地补偿表。
3. 线程池水位（active/queue）纳入监控，queue>50% 预警。

## 复盘教训

- 共享线程池是故障传播通道，`50092_THREAD_POOL_EXHAUSTED` 的根因往往在别处（本例是 `50151_FEIGN_TIMEOUT`）。
- 异步化不等于安全化，拒绝策略与隔离设计必须前置。
