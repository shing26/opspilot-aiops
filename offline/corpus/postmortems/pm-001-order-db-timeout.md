---
doc_id: pm-001
service: order-service
env: prod
auth_level: 2
error_codes: [50012_DB_TIMEOUT, 50101_SLOW_SQL_DETECTED, 50141_CONNECTION_POOL_EXHAUSTED]
date: 2025-11-14
severity: P1
---

# 订单创建接口大面积超时复盘（50012_DB_TIMEOUT）

## 事故摘要

2025-11-14 20:07 大促开始后 3 分钟，`POST /api/v1/orders` 成功率从 99.9% 跌至 61%，网关侧集中抛出 `50012_DB_TIMEOUT`，影响持续 22 分钟。根因为慢 SQL 拖垮 HikariCP 连接池，最终触发 `50141_CONNECTION_POOL_EXHAUSTED`。

## 时间线

| 时间 | 事件 |
| --- | --- |
| 20:07 | 大促流量进入，order-service QPS 从 800 升至 4200 |
| 20:09 | 告警：`50012_DB_TIMEOUT` 突增至 1200/min |
| 20:11 | 定位到 `OrderQueryService#listOrders` 大分页 SQL 未走索引 |
| 20:15 | HikariCP active=50（池上限），等待线程堆积 |
| 20:29 | 强制重启 + 限流降级，成功率恢复 |

## 根因分析

慢 SQL 来自运营后台导出的大分页查询，与下单共用同一主库连接池：

```sql
SELECT o.*, oi.* FROM t_order o
JOIN t_order_item oi ON oi.order_id = o.id
WHERE o.user_id = ? ORDER BY o.create_time DESC
LIMIT 100000, 20;   -- 深分页，回表 10 万行
```

堆栈特征（用于精确匹配）：

```
com.ordercenter.order.OrderCreateService.createOrder(OrderCreateService.java:148)
Caused by: com.mysql.cj.jdbc.exceptions.CJTimeoutException:
    Statement cancelled due to timeout, error code 50012_DB_TIMEOUT
```

## 修复措施

1. 读写分离：列表查询切至只读实例，下单主链路独占主库连接池。
2. 深分页改造为游标分页（`WHERE create_time < ?`）。
3. HikariCP 按业务隔离：`order-write-pool`（50）与 `order-read-pool`（20）。

## 复盘教训

- 连接池共享是隐性故障放大器，`50012_DB_TIMEOUT` 只是表象，`50101_SLOW_SQL_DETECTED` 早 4 分钟已告警但被忽略。
- 大促前必须演练 `50141_CONNECTION_POOL_EXHAUSTED` 的降级预案。
