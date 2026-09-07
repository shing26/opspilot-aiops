---
doc_id: rb-006
service: order-service
env: prod
auth_level: 1
error_codes: [40901_ORDER_STATE_CONFLICT]
---

# 订单状态冲突排查手册（40901_ORDER_STATE_CONFLICT）

## 适用症状

- 取消/支付/发货接口返回 `40901_ORDER_STATE_CONFLICT`
- 日志出现 `OrderStateConflictException: illegal transition`

## 判断是否预期内冲突

| 状态迁移 | 是否预期 | 处理 |
| --- | --- | --- |
| PAID → CANCELLED | 预期（并发竞争） | 记 WARN，不告警 |
| SHIPPED → CANCELLED | 非预期 | 立即排查数据异常 |
| CREATED → PAID 重复 | 预期（幂等命中） | 返回当前状态 |

## 排查步骤

### 第一步：查订单状态轨迹

```sql
SELECT order_id, from_status, to_status, operator, create_time
FROM t_order_status_log
WHERE order_id = ? ORDER BY create_time;
```

### 第二步：确认并发来源

`operator` 字段区分 `scheduler-timeout-cancel`（定时任务）与 `user-pay`（用户支付）。两者时间戳相差 <1s 即为竞争冲突。

### 第三步：检查分布式锁

```bash
redis-cli EXISTS order:cancel:<orderId>
```

锁缺失说明取消任务未加锁（见 pm-009）。

## 止损操作

1. 预期内冲突：无需止损，确认告警已分级降噪。
2. 非预期迁移（如 SHIPPED 被取消）：冻结该订单，人工介入对账。

## 升级路径

- 非预期冲突 >5 单 → 升级订单域 Owner。
- 涉及资损 → 同步资金安全组。
