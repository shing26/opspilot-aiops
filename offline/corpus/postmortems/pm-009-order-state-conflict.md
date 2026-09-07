---
doc_id: pm-009
service: order-service
env: prod
auth_level: 2
error_codes: [40901_ORDER_STATE_CONFLICT]
date: 2026-05-06
severity: P3
---

# 订单状态机冲突告警风暴复盘（40901_ORDER_STATE_CONFLICT）

## 事故摘要

2026-05-06 凌晨定时任务批量取消超时订单，与用户并发支付产生状态竞争，`40901_ORDER_STATE_CONFLICT` 瞬时 4000+ 条，触发告警风暴，值班群刷屏 200+ 条。

## 根因分析

取消任务与支付回调都走 `OrderStateMachine#transit`，但取消任务未加分布式锁，对「已支付」订单重复发起取消：

```
com.ordercenter.common.exception.OrderStateConflictException:
    illegal transition PAID -> CANCELLED, error code 40901_ORDER_STATE_CONFLICT
    orderId=88342911 operator=scheduler-timeout-cancel
```

业务上这是**预期内冲突**（用户刚好在取消瞬间完成支付），但被当作 P2 告警全量推送。

## 修复措施

1. 状态机冲突按「预期/非预期」分级：`PAID -> CANCELLED` 记 WARN 不告警，其余保持告警。
2. 取消任务加 Redisson 分布式锁（`order:cancel:{orderId}`）。
3. 告警接入 OpsPilot 指纹去重：同状态对 30s 窗口聚合为 1 条。

## 复盘教训

- 告警风暴的杀伤力在于淹没真信号，`40901_ORDER_STATE_CONFLICT` 这类高频预期冲突必须降噪。
- 定时任务与在线流量的并发竞争要在设计期就声明优先级。
