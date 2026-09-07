---
doc_id: rb-010
service: logistics-service
env: prod
auth_level: 2
error_codes: [50082_K8S_NODE_NOT_READY, 50131_NTP_DRIFT]
---

# 节点 NotReady 与时钟漂移排查手册（50082_K8S_NODE_NOT_READY）

## 适用症状

- 发货接口返回 `50082_K8S_NODE_NOT_READY`
- 偶发签名 401，伴随 `50131_NTP_DRIFT`

## 排查步骤

### 第一步：节点状态与原因

```bash
kubectl describe node node-w-07 | grep -A 10 Conditions
kubectl get events --field-selector involvedObject.name=node-w-07
```

常见原因：磁盘压力（`DiskPressure`）、容器运行时挂、kubelet 心跳丢失。

### 第二步：时钟漂移检查

```bash
chronyc tracking          # System time 偏移量
timedatectl status        # NTP synchronized: no 即异常
```

偏移 >1s 即可能触发 `50131_NTP_DRIFT`（JWT 未来时间被拒）。

### 第三步：驱逐与调度

```bash
kubectl get pods -A --field-selector spec.nodeName=node-w-07
```

## 止损操作

1. `kubectl cordon node-w-07` 禁止新调度，`kubectl drain --ignore-daemonsets` 驱逐业务 Pod。
2. 时钟漂移：`chronyc makestep` 强制校时（注意跳变对租约的影响）。
3. 磁盘压力：`crictl rmi --prune` 清理镜像。

## 升级路径

- 多节点同时 NotReady → P1，升级平台组排查基础设施。
- 校时后仍有签名失败 → 检查下游是否缓存了旧时间窗口。
