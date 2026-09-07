---
doc_id: pm-004
service: user-service
env: prod
auth_level: 3
error_codes: [50022_REDIS_CONN_REFUSED, 50071_CONFIG_CENTER_UNREACHABLE]
date: 2026-02-19
severity: P1
---

# Session 服务 Redis 连接拒绝复盘（50022_REDIS_CONN_REFUSED）

## 事故摘要

2026-02-19 03:14，登录接口成功率跌至 12%，集中抛 `50022_REDIS_CONN_REFUSED`。根因为 Redis 集群扩缩容期间，应用侧连接池仍持有旧分片地址。

## 根因分析

Nacos 配置中心当时处于滚动重启（`50071_CONFIG_CENTER_UNREACHABLE` 前兆告警已出现），Lettuce 拓扑刷新被禁用，客户端无法感知分片变更：

```
com.ordercenter.user.SessionService.createSession(SessionService.java:58)
Caused by: io.lettuce.core.RedisConnectionException:
    Unable to connect to redis-session-2.prod.internal:6380, error code 50022_REDIS_CONN_REFUSED
```

生产 Redis 集群拓扑（敏感，auth_level=3）：

```yaml
# nacos: user-service/prod/redis-cluster.yaml
cluster:
  nodes:
    - redis-session-0.prod.internal:6379
    - redis-session-1.prod.internal:6379
    - redis-session-2.prod.internal:6380   # 本次扩容新增分片
  topology-refresh-period: 0   # 事故根因：拓扑刷新被关闭
```

## 修复措施

1. 开启 `topology-refresh-period=30s` 与自适应刷新触发。
2. 扩缩容 SOP 增加「先刷新客户端拓扑、再切流」步骤。
3. 配置中心不可达时，本地缓存最近一次有效配置（fail-safe）。

## 复盘教训

- `50022_REDIS_CONN_REFUSED` 与 `50071_CONFIG_CENTER_UNREACHABLE` 同时出现时，优先怀疑拓扑/配置类变更而非网络。
- 生产连接串等配置必须密级管控，本手册 auth_level=3 即因此。
