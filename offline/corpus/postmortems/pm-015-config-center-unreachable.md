---
doc_id: pm-015
service: order-service
env: prod
auth_level: 2
error_codes: [50071_CONFIG_CENTER_UNREACHABLE, 42901_RATE_LIMIT_EXCEEDED]
date: 2026-08-02
severity: P1
---

# 配置中心不可达引发限流误触发复盘（50071_CONFIG_CENTER_UNREACHABLE）

## 事故摘要

2026-08-02 15:30，Nacos 集群网络分区，`50071_CONFIG_CENTER_UNREACHABLE` 告警刷屏，order-service 拉取限流规则失败，Sentinel 规则回退默认值（QPS 阈值 50），大促流量瞬间触发 `42901_RATE_LIMIT_EXCEEDED`，下单成功率跌至 30%。

## 根因分析

限流规则托管在 Nacos，客户端无本地快照兜底，配置拉取失败时直接采用内置默认值：

```
com.alibaba.nacos.client.config.impl.ClientWorker - longPolling error
    error code 50071_CONFIG_CENTER_UNREACHABLE
com.ordercenter.order.OrderFlowRuleLoader - fallback to default QPS=50
    -> 42901_RATE_LIMIT_EXCEEDED 12000/min
```

## 修复措施

1. 限流规则本地快照持久化（`/data/sentinel/snapshot.json`），配置中心不可达时读最近快照。
2. 默认值策略改为「保持当前生效规则」而非「回退保守值」。
3. `50071_CONFIG_CENTER_UNREACHABLE` 升级为 P1，联动暂停自动扩缩容。

## 复盘教训

- 配置中心的可用性假设必须被打破，所有动态规则都要有本地兜底。
- `42901_RATE_LIMIT_EXCEEDED` 大面积出现时，先确认是「真过载」还是「规则丢失」。
