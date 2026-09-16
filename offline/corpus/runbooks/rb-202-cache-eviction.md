---
doc_id: rb-202
service: opspilot-redis
env: local
auth_level: 1
error_codes: [51202_CACHE_EVICTED_EARLY]
date: 2026-09-11
---

# 缓存命中率骤降排查手册（51202_CACHE_EVICTED_EARLY）

## 适用症状

- `metrics.l1_cache_hits` / `metrics.l2_cache_hits` 占比明显低于预期，同一 query 短时间内反复调用 LLM
- 高峰期（告警风暴、压测）尤其明显，低峰期正常
- 网关日志干净：没有异常、没有超时，只是"缓存像不存在"
- 成本侧先于可用性报警：LLM 调用量与 token 消耗随流量非线性上升

## 排查步骤

### 第一步：区分"没写进去"与"写进去又被赶出来"

1. 取一个刚发过的 query，立刻重放：若立刻命中（`meta.cache_hit=L1`）→ 写入正常，问题在寿命
2. 直接查 Redis：`INFO memory`（`maxmemory`、`used_memory`、`maxmemory_policy`）
3. 查 `INFO stats` 的 `evicted_keys`：**持续增长即判"被淘汰"**，这是本故障的决定性证据
4. 若 `evicted_keys` 不动而命中率仍低 → 改查键是否存在与是否过期（`TTL <key>`），方向转到 TTL 计算/时钟

### 第二步：量化"实际寿命 vs 契约寿命"

5. 对照配置契约（`opspilot.cache.l1-ttl-hours`，默认 2h）
6. 用监控或 `redis-cli --scan` 采样一批键看剩余 TTL 分布；若普遍远小于契约 → 契约与容量策略矛盾
7. 确认被淘汰的键是否可再生：本系统 L1/L2 缓存与风暴窗口键**全部带 TTL 且可再生**（前提成立）

### 第三步：定位是谁在挤谁

8. `redis-cli --bigkeys` 或按前缀统计内存占用：区分缓存载荷（`cache:l1:*`）与窗口 ZSET（`storm:win:*`）
9. 若窗口键在风暴期膨胀明显 → 属"写入峰值超容量"，不是缓存逻辑缺陷

## 止损操作

1. 扩容优先于换策略：`--maxmemory` 上调（本系统实测 96mb → 256mb 解决），重启 Redis 生效
2. **不要**改成 `noeviction`：内存满时写入直接报错，会把缓存失效升级为业务失败
3. 短期降载：收敛告警风暴（`source=alert` 的窗口聚合仍在工作），减少峰值写入
4. 扩容后复验三件事：命中率回升、`evicted_keys` 停止增长、LLM 调用量回到基线

## 升级路径

- 容量已上调仍反复淘汰 → 评估是否需要拆库（缓存与窗口分实例）或缩短窗口/载荷体积
- 若未来引入**不可再生**的键（如任务状态）→ 必须先重新论证淘汰策略，本手册的前提即失效
