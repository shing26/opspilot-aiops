---
doc_id: pm-014
service: inventory-service
env: prod
auth_level: 3
error_codes: [50121_DNS_RESOLUTION_FAILED, 50071_CONFIG_CENTER_UNREACHABLE]
date: 2026-07-19
severity: P2
---

# 库存同步 DNS 解析失败复盘（50121_DNS_RESOLUTION_FAILED）

## 事故摘要

2026-07-19 11:05，`POST /api/v1/inventory/sync` 批量失败，错误码 `50121_DNS_RESOLUTION_FAILED`，WMS 域名无法解析，库存同步中断 18 分钟。

## 根因分析

CoreDNS 转发上游 DNS 配置指向已下线的内网解析器，且 ndots 配置不当导致每次解析产生 4 次 search 域拼接查询，放大故障：

```
com.ordercenter.inventory.WmsSyncClient.sync(WmsSyncClient.java:53)
java.net.UnknownHostException: wms.internal.example.com
    error code 50121_DNS_RESOLUTION_FAILED
```

生产 CoreDNS 配置片段（auth_level=3）：

```yaml
# configmap: kube-system/coredns
forward . 10.20.30.40 10.20.30.41   # 事故根因：10.20.30.41 已下线
cache 30
```

Pod 内 `/etc/resolv.conf`：

```
nameserver 10.96.0.10
options ndots:5   # 放大查询次数
```

## 修复措施

1. CoreDNS 上游更新为存活解析器并加 `max_concurrent` 限制。
2. 业务域名解析改用 FQDN 结尾（`wms.internal.example.com.`）绕过 search 拼接。
3. `50121_DNS_RESOLUTION_FAILED` 与 `50071_CONFIG_CENTER_UNREACHABLE` 联动排查网络基础设施。

## 复盘教训

- DNS 是「隐形基础设施」，`50121_DNS_RESOLUTION_FAILED` 爆发时先查 CoreDNS 而非应用。
- ndots:5 是 K8s 默认坑，内网域名务必带尾点。
