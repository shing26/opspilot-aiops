---
doc_id: pm-003
service: cart-service
env: prod
auth_level: 2
error_codes: [50021_REDIS_TIMEOUT, 50161_SENTINEL_BLOCKED]
date: 2026-01-08
severity: P2
---

# 购物车 Redis 超时雪崩复盘（50021_REDIS_TIMEOUT）

## 事故摘要

2026-01-08 11:20，Redis 集群单分片发生大 Key 迁移，`GET /api/v1/cart` 大面积 `50021_REDIS_TIMEOUT`，购物车页白屏 9 分钟。

## 根因分析

购物车缓存按用户维度存 Hash，但运营活动把「凑单推荐」写进了同一个 Key 的 field，单 Key 膨胀至 18MB：

```
com.ordercenter.cart.CartCacheService.getCart(CartCacheService.java:72)
Caused by: io.lettuce.core.RedisCommandTimeoutException:
    Command timed out after 200 millisecond(s), error code 50021_REDIS_TIMEOUT
```

超时后请求穿透至 DB，DB 连接池打满，Sentinel 对加购接口触发 `50161_SENTINEL_BLOCKED` 形成二次故障。

## 修复措施

1. 大 Key 拆分：凑单推荐独立 Key `cart:rec:{userId}`，TTL 5 分钟。
2. 购物车读增加本地 Caffeine 兜底（30s），Redis 超时降级读本地。
3. `50021_REDIS_TIMEOUT` 告警阈值从 500/min 收紧至 100/min。

## 复盘教训

- 大 Key 是集群迁移的隐形炸弹，上线前必须 `redis-cli --bigkeys` 巡检。
- 缓存雪崩的止损顺序：先限流保 DB，再恢复缓存，而不是反过来。
