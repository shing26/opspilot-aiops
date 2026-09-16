---
doc_id: pm-202
service: opspilot-redis
env: local
auth_level: 1
error_codes: [51202_CACHE_EVICTED_EARLY]
date: 2026-09-11
severity: P2
---

# 复盘：Redis 容量策略把 2h 的 L1 缓存压成 3 分钟寿命（51202_CACHE_EVICTED_EARLY）

## 事故摘要

告警风暴压测中观察到：同一 query 在几分钟内反复穿透到 LLM，与"L1 精确缓存 TTL 2 小时"的配置契约
严重不符。查 Redis 侧发现键被**提前淘汰**——契约写的寿命是 2h，实际存活不到 3min，而网关侧
没有任何报错：TTL 未被修改，是键本身不在了。

## 时间线

- 压测（500 并发同指纹）期间 L1 命中率异常低，重复请求持续触发 LLM 调用
- 网关侧查缓存读写逻辑正常，TTL 设置也确实是 2h
- 转而查 Redis：`maxmemory` 96mb，策略 `allkeys-lru`，`evicted_keys` 大量增长
- 确认根因后扩容量至 256mb，压测复现不再淘汰

## 根因分析

风暴期两类键同时增长：滑动窗口的 ZSET（按指纹累积、窗口期内持续写入）与 L1 缓存载荷（单条答案
JSON，含引用），瞬时占用超过 96mb 上限；而 `allkeys-lru` 的策略是"任意键都可被淘汰"，
于是**正在使用的 L1 键被窗口键挤掉**。TTL 只是"最多活多久"，容量策略才决定"能不能活到那时"——
两者是彼此独立的失效面，配置正确不代表行为正确。

## 修复措施

扩容而非换策略：所有键都带 TTL、且全部是可再生数据（缓存重算、窗口重计），
换 `noeviction` 会让写入直接失败、换 `volatile-*` 也只是把选择权换个方向，都不如扩容直接。

```yaml
command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru --save "" --requirepass ${REDIS_PASSWORD}
```

## 复盘教训

- **"设了 TTL" ≠ "能活到 TTL"**：任何缓存寿命宣称都必须同时说明容量与淘汰策略，否则是不可核验的承诺
- 缓存类指标要看"实际存活时长"而不是"配置的 TTL"；命中率骤降优先查 evicted_keys，不要先怀疑代码
- 淘汰策略的前提是"被淘汰的东西可再生"——本次成立（全部带 TTL），若哪天引入不可再生键，此策略即失效
