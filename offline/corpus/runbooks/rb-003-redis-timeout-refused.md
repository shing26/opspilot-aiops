---
doc_id: rb-003
service: cart-service
env: prod
auth_level: 1
error_codes: [50021_REDIS_TIMEOUT, 50022_REDIS_CONN_REFUSED]
---

# Redis 超时与连接拒绝排查手册（50021_REDIS_TIMEOUT / 50022_REDIS_CONN_REFUSED）

## 适用症状

- 购物车/券查询返回 `50021_REDIS_TIMEOUT`
- 登录返回 `50022_REDIS_CONN_REFUSED`

## 区分两类错误

| 错误码 | 含义 | 首查方向 |
| --- | --- | --- |
| 50021_REDIS_TIMEOUT | 连上了但命令超时 | 慢命令 / 大 Key / 网络抖动 |
| 50022_REDIS_CONN_REFUSED | 连不上 | 分片地址失效 / 扩缩容 / maxclients |

## 排查步骤

### 50021：慢命令与大 Key

```bash
redis-cli --latency -h redis-cart-0.prod.internal
redis-cli SLOWLOG GET 10
redis-cli --bigkeys
```

### 50022：拓扑与连接数

```bash
redis-cli -h redis-session-0.prod.internal INFO clients | grep connected
redis-cli -h redis-session-0.prod.internal CLUSTER NODES
```

若 `CLUSTER NODES` 中存在 `fail` 分片，检查应用侧 Lettuce 拓扑刷新是否开启。

## 止损操作

1. 超时：购物车读降级本地 Caffeine 缓存（30s TTL）。
2. 连接拒绝：确认扩缩容后执行客户端拓扑刷新，必要时滚动重启应用。
3. 大 Key 紧急处理：`UNLINK`（异步删除）而非 `DEL`。

## 升级路径

- 集群多分片 fail → 升级中间件组。
- 伴随配置中心告警 → 按 pm-004 联动排查。
