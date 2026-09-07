---
doc_id: rb-008
service: order-service
env: prod
auth_level: 1
error_codes: [42901_RATE_LIMIT_EXCEEDED, 50161_SENTINEL_BLOCKED]
---

# 限流与熔断排查手册（42901_RATE_LIMIT_EXCEEDED / 50161_SENTINEL_BLOCKED）

## 适用症状

- 接口返回 `42901_RATE_LIMIT_EXCEEDED` 或 `50161_SENTINEL_BLOCKED`
- 大促/秒杀场景大面积拒绝请求

## 关键判断：真过载还是规则丢失

| 信号 | 真过载 | 规则丢失 |
| --- | --- | --- |
| 实际 QPS | 超过阈值 | 远低于阈值仍被限 |
| 配置中心状态 | 正常 | `50071_CONFIG_CENTER_UNREACHABLE` |
| 阈值来源 | Nacos 动态规则 | 回退默认值 |

## 排查步骤

### 第一步：看 Sentinel 实时阈值

```bash
curl -s localhost:8719/sentinel/order-service/flow rules | jq '.[].count'
```

### 第二步：对比实际流量

```bash
curl -s localhost:8080/actuator/metrics/http.server.requests | jq '.measurements'
```

### 第三步：确认规则加载来源

```bash
ls -la /data/sentinel/snapshot.json   # 本地快照
grep "fallback to default" logs/order-service.log
```

## 止损操作

1. 真过载：水平扩容 + 前端排队页，禁止直接调高阈值（保护下游）。
2. 规则丢失：恢复 Nacos 连接，或手动下发快照规则（见 pm-015）。
3. 热点参数限流（`50161`）：对热点商品单独提阈值，全局阈值不动。

## 升级路径

- 限流误伤 >30% 流量 → P1，升级订单域 Owner。
- 伴随配置中心故障 → 按 pm-015 处理。
