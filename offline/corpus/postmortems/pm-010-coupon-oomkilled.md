---
doc_id: pm-010
service: coupon-service
env: prod
auth_level: 2
error_codes: [50081_K8S_POD_OOMKILLED]
date: 2026-05-20
severity: P2
---

# 大促发券 OOMKilled 复盘（50081_K8S_POD_OOMKILLED）

## 事故摘要

2026-05-20 20:00 整点发券，coupon-service 3 个 Pod 在 90 秒内相继被 OOMKilled（`50081_K8S_POD_OOMKILLED`），K8s 重启后再次 OOM，循环 4 次，发券接口不可用 11 分钟。

## 根因分析

批量发券接口把 50 万张券一次性加载进内存做去重，堆峰值 1.8G 超过 limit 1G：

```
kubectl describe pod coupon-service-7d9f4-abcde
  Last State: Terminated, Reason: OOMKilled, Exit Code: 137
  error code 50081_K8S_POD_OOMKILLED
```

```
com.ordercenter.coupon.CouponIssueService.issue(CouponIssueService.java:112)
java.lang.OutOfMemoryError: Java heap space
```

## 修复措施

1. 批量发券改为分页流式处理（每批 5000），去重下沉 Redis Set。
2. JVM 堆对齐容器 limit（`-XX:MaxRAMPercentage=70`）。
3. OOMKilled 事件接入告警并自动拉取 heap dump（`-XX:+HeapDumpOnOutOfMemoryError`）。

## 复盘教训

- 容器 limit 与 JVM 堆必须显式对齐，默认 MaxHeap 按宿主机内存计算是 OOMKilled 的头号诱因。
- 整点定时任务必须错峰 + 限流，`50081_K8S_POD_OOMKILLED` 从来不是突发，是必然。
