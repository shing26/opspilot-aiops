---
doc_id: pm-011
service: logistics-service
env: prod
auth_level: 2
error_codes: [50082_K8S_NODE_NOT_READY, 50131_NTP_DRIFT]
date: 2026-06-03
severity: P2
---

# 节点 NotReady 与 NTP 漂移叠加故障复盘（50082_K8S_NODE_NOT_READY）

## 事故摘要

2026-06-03 09:40，logistics-service 所在节点 `node-w-07` 变为 NotReady，Pod 驱逐导致发货接口 `POST /api/v1/logistics/orders/{orderId}/ship` 返回 `50082_K8S_NODE_NOT_READY`。恢复后发现同节点存在 3.2s 时钟漂移（`50131_NTP_DRIFT`），部分发货签名被下游拒绝。

## 根因分析

节点磁盘压力触发 kubelet 驱逐，同时 chrony 服务异常导致 NTP 漂移未被纠正：

```
kubectl get nodes node-w-07
  STATUS: NotReady,SchedulingDisabled   error code 50082_K8S_NODE_NOT_READY
chronyc tracking
  System time : 3.214 seconds fast      error code 50131_NTP_DRIFT
```

时钟漂移使 JWT 签发时间超前，下游网关按「未来时间」拒绝，表现为偶发 401。

## 修复措施

1. 节点磁盘告警前置（>80% 即清理镜像），驱逐设为最后防线。
2. chrony 纳入节点基线巡检，漂移 >1s 自动告警 `50131_NTP_DRIFT`。
3. 发货接口增加幂等重试，Pod 驱逐期间自动漂移到健康节点。

## 复盘教训

- `50082_K8S_NODE_NOT_READY` 常伴随次生故障（本例 NTP），排查要同时看节点级与应用级信号。
- 分布式系统对时钟的依赖远超直觉，签名/租约/限流都受影响。
