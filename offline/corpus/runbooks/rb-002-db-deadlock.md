---
doc_id: rb-002
service: inventory-service
env: prod
auth_level: 1
error_codes: [50013_DB_DEADLOCK]
---

# 数据库死锁排查手册（50013_DB_DEADLOCK）

## 适用症状

- 库存扣减/释放返回 `50013_DB_DEADLOCK`
- 日志出现 `MySQLTransactionRollbackException: Deadlock found`

## 排查步骤

### 第一步：抓取死锁现场

```sql
SHOW ENGINE INNODB STATUS\G
```

定位 `LATEST DETECTED DEADLOCK` 段，记录两条事务的 SQL 与持锁/等锁关系。

### 第二步：还原加锁顺序

| 事务 | 持锁 | 等锁 |
| --- | --- | --- |
| T1 | `t_inventory` 行锁 | `t_order` 行锁 |
| T2 | `t_order` 行锁 | `t_inventory` 行锁 |

两条事务以相反顺序访问同一组行即构成死锁环。

### 第三步：确认热点行

```sql
SELECT sku_id, version FROM t_inventory
WHERE sku_id IN (/* 死锁日志中的主键 */) FOR UPDATE NOWAIT;
```

## 止损操作

1. 开启 `DeadlockRetryAspect` 自动重试（指数退避，最多 3 次）。
2. 热点 SKU 切换 Redis 预扣模式：`DECRBY stock:{skuId}`，异步落库。
3. 秒杀场景下单行并发 >500 时，直接走队列串行化扣减。

## 升级路径

- 死锁频率 >10/min 持续 5 分钟 → 升级库存域 Owner。
- 涉及资金对账差异 → 同步通知支付域。
