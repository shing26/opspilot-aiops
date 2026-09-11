---
doc_id: ac-507
tenant: tenant-acme
service: acme-k8s-net
env: prod
auth_level: 2
error_codes: [52006_DNS_RESOLVER_FLAKE]
---

# 集群内 DNS 解析抖动（conntrack 竞争特征）

## 适用症状

- 服务间调用间歇性 5-30s 延迟，抓包可见 DNS 查询发出但响应丢失；
- 错误码 `52006_DNS_RESOLVER_FLAKE`，单 Pod 重启无效、跨节点随机出现。

## 排查步骤

1. 先在**节点级**而非 Pod 级取证：coredns 日志按客户端节点聚合，若丢包集中在
   某些节点 → 这些节点上有高并发外连业务（conntrack 表竞争是首要嫌疑）；
2. 验证：嫌疑节点 `conntrack -S` 看 insert_failed/drop 计数，与 coredns 丢包时间点
   对齐即实锤；
3. 若节点分散且 coredns 本身负载不高 → 查应用侧 resolv.conf 是否退化成
   `options attempts:2 timeout:2` 默认值，glibc A/AAAA 双查询放大在高峰期
   会把问题推后暴露为"偶发"；
4. 排除上游：托管 DNS 供应商状态页 + 递归解析链路 `dig +trace` 对比。

## 止损操作

- conntrack 竞争：给 DNS 端口段配置节点级 QoS（iptables mark），并把高外连业务
  与核心服务做节点亲和隔离（治本）；
- 临时缓解：业务侧启用本地 DNS 缓存（node-local dns 开关 `acme.net.nodelocal`）。

## 已知陷阱

- glibc 单容器内验证"解析正常"不能排除本问题——丢包率 <5% 时单次 dig 几乎必中，
  必须做 100 次批量对比测试（脚本 `dns-flake-probe`）。
