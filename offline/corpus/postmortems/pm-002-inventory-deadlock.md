---
doc_id: pm-002
service: inventory-service
env: prod
auth_level: 2
error_codes: [50013_DB_DEADLOCK, 40902_INVENTORY_INSUFFICIENT]
date: 2025-12-02
severity: P2
---

# 热点 SKU 扣减死锁复盘（50013_DB_DEADLOCK）

## 事故摘要

2025-12-02 秒杀活动开始，`POST /api/v1/inventory/deduct` 出现 3.2% 失败率，错误码 `50013_DB_DEADLOCK`。热点 SKU（iPhone 17 Pro）单行并发更新导致 InnoDB 行锁死锁。

## 根因分析

扣减与释放两条事务以不同顺序加锁（先 SKU 行锁、后订单行锁 vs 反之），交叉等待：

```
com.ordercenter.inventory.StockDeductService.deduct(StockDeductService.java:96)
Caused by: com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException:
    Deadlock found when trying to get lock; error code 50013_DB_DEADLOCK
```

`SHOW ENGINE INNODB STATUS` 中 LATEST DETECTED DEADLOCK 段确认两条 UPDATE 互等。

## 修复措施

1. 统一加锁顺序：所有事务先锁 `t_inventory` 再锁 `t_order`。
2. 引入 `DeadlockRetryAspect` 对 `50013_DB_DEADLOCK` 做指数退避重试（最多 3 次）。
3. 热点 SKU 扣减改走 Redis 预扣 + 异步落库（见 rb-007）。

## 复盘教训

- 死锁日志必须保留 `LATEST DETECTED DEADLOCK` 原文，否则无法还原加锁顺序。
- `40902_INVENTORY_INSUFFICIENT` 与死锁并发出现时，优先排查锁而非库存真实水位。
