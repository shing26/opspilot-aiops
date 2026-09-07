---
doc_id: rb-009
service: coupon-service
env: prod
auth_level: 1
error_codes: [50081_K8S_POD_OOMKILLED]
---

# K8s Pod OOMKilled 排查手册（50081_K8S_POD_OOMKILLED）

## 适用症状

- Pod 反复重启，`kubectl describe pod` 显示 `Reason: OOMKilled, Exit Code: 137`
- 错误码 `50081_K8S_POD_OOMKILLED`

## 排查步骤

### 第一步：确认是容器级 OOM 还是 JVM OOM

```bash
kubectl describe pod <pod> | grep -A 5 "Last State"
```

- `Reason: OOMKilled` → 容器内存超 limit（本手册）。
- `java.lang.OutOfMemoryError` 但容器未杀 → JVM 堆配置问题（见 rb-011）。

### 第二步：看内存曲线

```bash
# Prometheus
container_memory_working_set_bytes{pod="<pod>"}
```

阶梯式上涨 → 内存泄漏；瞬时尖峰 → 大对象/批量加载。

### 第三步：对齐 JVM 与容器 limit

```bash
kubectl exec <pod> -- java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
```

MaxHeap 应 ≤ limit × 70%。

## 止损操作

1. 临时提升 limit（`kubectl patch`），争取排查时间。
2. 批量任务改分页流式处理（见 pm-010）。
3. 摘除故障 Pod 流量，保留一个现场 Pod 抓 heap dump。

## 升级路径

- 多服务同时 OOMKilled → 怀疑节点级内存压力，升级平台组。
- 确认泄漏 → 提供 heap dump 给应用 Owner 分析。
