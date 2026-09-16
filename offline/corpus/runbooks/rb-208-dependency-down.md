---
doc_id: rb-208
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51208_DEPENDENCY_DOWN]
date: 2026-09-16
---

# 依赖组件不可用（Redis/ES/Qdrant）排查手册（51208_DEPENDENCY_DOWN）

## 适用症状

- `/api/v1/admin/state` 的 `health.status = DEGRADED`，`health.{redis|es|qdrant}.status = DOWN`
- 检索结果异常：`mode` 静默退化为 `es_only`（向量腿不可用）或召回为空（倒排腿不可用）
- 缓存命中率骤降、`metrics.retrieval_timeouts` 增长
- 自举告警源发出的告警文案形如"依赖组件 X 健康检查异常"，`service` 为组件名

## 排查步骤

### 第一步：先直连依赖确认，别只信网关侧观测

1. Redis：`docker exec <redis> redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping`
2. ES：`curl -u elastic:"$ES_PASSWORD" http://localhost:9200/_cluster/health`
3. Qdrant：`curl -H "api-key: $QDRANT_API_KEY" http://localhost:6333/healthz`
4. **外部探活 UP 而网关侧仍报 DOWN** = 客户端通道未恢复，见下面"止损操作"第 3 条（这是本系统实测到的恢复滞后，不是误报）

### 第二步：判定影响面（哪种退化）

5. `metrics.es_only_requests` 增长 → 向量腿不可用，检索退化为词法单路：精确符号仍准，语义召回变差
6. 检索结果为空且 `refused=true` → 倒排腿也不可用，系统会显式拒答（不会编答案）
7. `metrics.l1_cache_hits/l2_cache_hits` → Redis 不可用时缓存全失效，LLM 调用量与成本立刻上升
8. 看 `runtime.degradation.level`：依赖故障持续会推高在途数与失败计数，可能叠加触发 L1/L2 降级

### 第三步：恢复顺序与验证

9. 依赖按"被依赖顺序"恢复：Redis → ES → Qdrant（网关侧连接是懒重建，顺序不影响正确性，只影响恢复速度）
10. 恢复后**以直连探针 + 一次真实检索**双重确认，不要只看容器状态

## 止损操作

1. **保可用**：单腿不可用时系统已自动降级（`es_only` / 拒答），无需人工干预即可继续提供部分服务；
   但要在对外说明里写清"当前为降级态，语义召回受限"
2. **保成本**：Redis 不可用意味着缓存全失，风暴期 LLM 成本会飙升——必要时先收敛告警源与并发
3. **恢复滞后要认**：本系统实测 `docker compose stop/start qdrant` 后，**网关侧 qdrant 健康面在
   12 秒时仍为 DOWN，约 75 秒内自行转 UP**（gRPC 通道重建耗时）。此期间 `_healthy` 判据应看
   直连探针；若长时间不恢复（数分钟量级）再考虑重启网关强制重建连接
4. 恢复后核对三件事：`health.status` 回 UP、一次 `mode=hybrid` 检索不再 `degraded`、缓存开始命中
5. 若为数据面受损（ES/Qdrant 数据卷异常），恢复后必须重灌：`POST /api/v1/admin/reingest`（不带 body），
   核对 ES 计数与 Qdrant points 数等于语料数

## 升级路径

- 依赖反复不可用 → 从"救火"转"根因"：查资源限额（内存/磁盘）、宿主资源争抢、数据卷健康
- 恢复滞后超时（分钟级不恢复）→ 属客户端连接管理问题，需评估连接重建策略（并先写 ADR）
- 需要"依赖故障自动切换"→ 当前设计是降级而非多活，属架构变更，另行评估
