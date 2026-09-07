---
doc_id: rb-001
service: order-service
env: prod
auth_level: 1
error_codes: [50012_DB_TIMEOUT, 50141_CONNECTION_POOL_EXHAUSTED, 50101_SLOW_SQL_DETECTED]
---

# 订单创建超时排查手册（50012_DB_TIMEOUT）

## 适用症状

- 下单接口返回 `50012_DB_TIMEOUT`，或日志出现 `CJTimeoutException`
- 伴随 `50141_CONNECTION_POOL_EXHAUSTED` 或 `50101_SLOW_SQL_DETECTED`

## 排查步骤

### 第一步：确认是数据库慢还是连接池满

```sql
-- 查看当前活跃连接与等待
SELECT * FROM information_schema.processlist
WHERE command != 'Sleep' ORDER BY time DESC LIMIT 20;
```

- 若大量连接 `time > 5s` 且 `state = 'Sending data'` → 慢 SQL，转第二步。
- 若 processlist 正常但应用侧报 `HikariPool-1 - Connection is not available` → 连接池耗尽，转第三步。

### 第二步：定位慢 SQL

```sql
SHOW FULL PROCESSLIST;
EXPLAIN SELECT ... ;  -- 对疑似 SQL 执行，重点看 key 与 rows
```

关注 `type=ALL`（全表扫描）与 `rows > 10000` 的查询。

### 第三步：连接池排查

```bash
# 应用指标端点
curl -s localhost:8080/actuator/metrics/hikaricp.connections.active | jq
curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq
```

`pending > 0` 持续增长说明池上限不足或存在连接泄漏。

## 止损操作

1. 对慢 SQL 来源接口临时限流（Sentinel 控制台）。
2. 必要时 `KILL <processlist_id>` 终止长查询。
3. 连接池耗尽时重启实例释放泄漏连接（先摘流量）。

## 升级路径

- 10 分钟内无法定位 → 升级 DBA，提供 processlist 快照与慢日志。
- 涉及主库 → 走 P1 流程，禁止直接 `KILL` 写事务。
